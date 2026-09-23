import 'dart:io';

import 'package:flutter/material.dart';
import 'package:image_picker/image_picker.dart';

import '../../core/api_exception.dart';
import '../../core/uuid.dart';
import '../auth/auth_controller.dart';
import '../diagram/diagram_models.dart';
import '../diagram/diagram_stomp_service.dart';
import 'vision_import_api.dart';
import 'vision_import_logic.dart';
import 'vision_import_models.dart';

enum _Phase { idle, uploading, review, error }

class _DraftClassRow {
  _DraftClassRow(this.classEntity) : nameController = TextEditingController(text: classEntity.name);

  final ClassEntity classEntity;
  final TextEditingController nameController;
  bool included = true;

  void dispose() => nameController.dispose();
}

class _DraftRelationshipRow {
  _DraftRelationshipRow(this.relationship);

  final Relationship relationship;
  bool included = true;
}

/// CU12 (Importar Diagrama desde Foto de Pizarra), flujo Human-in-the-Loop:
/// sacar/elegir foto → `POST vision-import` → revisar el borrador (excluir o
/// renombrar clases/relaciones) → al confirmar, cada elemento incluido se
/// manda como ADD_CLASS/ADD_RELATIONSHIP por el MISMO canal STOMP de
/// mutaciones que ya reconstruye el modelo en Fase 2 (`applyOperation`) — no
/// hay reducer nuevo, el eco del propio broadcast actualiza la vista.
///
/// Reusa el `DiagramStompService` YA conectado de `DiagramViewScreen` (se lo
/// pasamos por constructor) en vez de abrir una conexión STOMP nueva.
class VisionImportScreen extends StatefulWidget {
  const VisionImportScreen({super.key, required this.diagramId, required this.stompService});

  final String diagramId;
  final DiagramStompService stompService;

  @override
  State<VisionImportScreen> createState() => _VisionImportScreenState();
}

class _VisionImportScreenState extends State<VisionImportScreen> {
  final _picker = ImagePicker();
  final _api = const VisionImportApi();

  _Phase _phase = _Phase.idle;
  File? _imageFile;
  String? _errorMessage;
  DraftModelResponse? _draft;
  List<_DraftClassRow> _classRows = [];
  List<_DraftRelationshipRow> _relationshipRows = [];
  bool _isConfirming = false;

  @override
  void dispose() {
    for (final row in _classRows) {
      row.dispose();
    }
    super.dispose();
  }

  Future<void> _pickAndUpload(ImageSource source) async {
    XFile? picked;
    try {
      // maxWidth/maxHeight + imageQuality: image_picker reescala y reencodea
      // de verdad del lado nativo (no es solo un hint) — necesario porque una
      // foto de cámara en resolución completa (varios MB) supera el límite
      // de tamaño de multipart del backend (413 Payload Too Large). Se fijan
      // los dos, no solo el ancho, para acotar el lado más largo sin importar
      // si la foto quedó en horizontal o vertical.
      picked = await _picker.pickImage(
        source: source,
        maxWidth: 1920,
        maxHeight: 1920,
        imageQuality: 85,
      );
    } catch (e) {
      setState(() {
        _phase = _Phase.error;
        _errorMessage = source == ImageSource.camera
            ? 'No se pudo acceder a la cámara. Revisá que el permiso esté habilitado.'
            : 'No se pudo abrir la galería.';
      });
      return;
    }
    if (picked == null) return; // el usuario canceló

    final file = File(picked.path);
    setState(() {
      _imageFile = file;
      _phase = _Phase.uploading;
      _errorMessage = null;
    });

    try {
      final draft = await _api.analyzeSketch(widget.diagramId, file);
      if (!mounted) return;
      if (draft.classes.isEmpty && draft.relationships.isEmpty) {
        setState(() {
          _phase = _Phase.error;
          _errorMessage = 'No se detectó ninguna clase ni relación en la imagen.';
        });
        return;
      }
      for (final row in _classRows) {
        row.dispose();
      }
      setState(() {
        _draft = draft;
        _classRows = draft.classes.map((c) => _DraftClassRow(c)).toList();
        _relationshipRows = draft.relationships.map((r) => _DraftRelationshipRow(r)).toList();
        _phase = _Phase.review;
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() {
        _phase = _Phase.error;
        _errorMessage = e.message;
      });
    }
  }

  String _classNameById(String id) {
    for (final row in _classRows) {
      if (row.classEntity.id == id) return row.nameController.text;
    }
    return '(clase ya existente en el diagrama)';
  }

  Future<void> _confirm() async {
    final userId = AuthController.instance.session.value?.userId;
    final draft = _draft;
    if (userId == null || draft == null) return;

    if (!widget.stompService.isConnected) {
      setState(() {
        _phase = _Phase.error;
        _errorMessage = 'Se perdió la conexión en tiempo real. Volvé al diagrama, esperá a que reconecte e intentá de nuevo.';
      });
      return;
    }

    setState(() => _isConfirming = true);

    final includedRows = _classRows.where((row) => row.included).toList();
    final includedClasses =
        includedRows.map((row) => row.classEntity.copyWith(name: row.nameController.text.trim())).toList();

    for (final classEntity in includedClasses) {
      widget.stompService.sendMutation(
        diagramId: widget.diagramId,
        operationType: 'ADD_CLASS',
        userId: userId,
        payload: classEntity.toJson(),
      );
    }

    final includedRelationshipIds = _relationshipRows.where((row) => row.included).map((row) => row.relationship.id).toSet();
    final relationshipsToApply = selectRelationshipsToApply(
      includedClasses: includedClasses,
      matchedExistingClassIds: draft.matchedExistingClassIds,
      relationships: draft.relationships,
      isIncluded: (r) => includedRelationshipIds.contains(r.id),
    );

    for (final relationship in relationshipsToApply) {
      widget.stompService.sendMutation(
        diagramId: widget.diagramId,
        operationType: 'ADD_RELATIONSHIP',
        userId: userId,
        payload: relationship.withNewId(generateUuidV4()).toJson(),
      );
    }

    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Importar foto de pizarra')),
      body: switch (_phase) {
        _Phase.idle => _buildIdle(context),
        _Phase.uploading => _buildUploading(context),
        _Phase.review => _buildReview(context),
        _Phase.error => _buildError(context),
      },
    );
  }

  Widget _buildIdle(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.camera_alt_outlined, size: 64),
            const SizedBox(height: 12),
            const Text(
              'Sacá una foto de una pizarra o un papel con un diagrama dibujado a mano.',
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              icon: const Icon(Icons.camera_alt),
              label: const Text('Sacar foto'),
              onPressed: () => _pickAndUpload(ImageSource.camera),
            ),
            const SizedBox(height: 8),
            OutlinedButton.icon(
              icon: const Icon(Icons.photo_library_outlined),
              label: const Text('Elegir de la galería'),
              onPressed: () => _pickAndUpload(ImageSource.gallery),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildUploading(BuildContext context) {
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_imageFile != null)
            ConstrainedBox(
              constraints: const BoxConstraints(maxHeight: 240),
              child: Image.file(_imageFile!),
            ),
          const SizedBox(height: 16),
          const CircularProgressIndicator(),
          const SizedBox(height: 12),
          const Text('Analizando la imagen…'),
        ],
      ),
    );
  }

  Widget _buildError(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, color: Theme.of(context).colorScheme.error, size: 48),
            const SizedBox(height: 12),
            Text(_errorMessage ?? 'Ocurrió un error.', textAlign: TextAlign.center),
            const SizedBox(height: 20),
            FilledButton(
              onPressed: () => setState(() => _phase = _Phase.idle),
              child: const Text('Reintentar con otra imagen'),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: () => Navigator.of(context).pop(),
              child: const Text('Cancelar'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildReview(BuildContext context) {
    final draft = _draft!;
    return Column(
      children: [
        if (_imageFile != null)
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 160),
            child: Image.file(_imageFile!),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 4),
          child: Text(
            'Revisá lo que se detectó antes de agregarlo al diagrama (podés desmarcar o renombrar):',
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            children: [
              ..._classRows.map((row) => _ClassDraftTile(row: row)),
              if (_relationshipRows.isNotEmpty) ...[
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 8),
                  child: Text('Relaciones detectadas', style: TextStyle(fontWeight: FontWeight.bold)),
                ),
                ..._relationshipRows.map(
                  (row) => CheckboxListTile(
                    dense: true,
                    controlAffinity: ListTileControlAffinity.leading,
                    value: row.included,
                    onChanged: (value) => setState(() => row.included = value ?? true),
                    title: Text(
                      '${_classNameById(row.relationship.sourceClassId)} — '
                      '${relationshipTypeLabel(row.relationship.type)} → '
                      '${_classNameById(row.relationship.targetClassId)}',
                    ),
                  ),
                ),
              ],
              if (draft.matchedExistingClassIds.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 8),
                  child: Text(
                    '${draft.matchedExistingClassIds.length} clase(s) ya existente(s) en el diagrama, no se duplican.',
                    style: Theme.of(context).textTheme.bodySmall,
                  ),
                ),
              if (draft.warnings.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: draft.warnings
                        .map((w) => Text('⚠ $w', style: TextStyle(color: Theme.of(context).colorScheme.error)))
                        .toList(),
                  ),
                ),
            ],
          ),
        ),
        SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: _isConfirming ? null : () => Navigator.of(context).pop(),
                    child: const Text('Cancelar'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: _isConfirming ? null : _confirm,
                    child: _isConfirming
                        ? const SizedBox(
                            height: 18,
                            width: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Agregar al diagrama'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class _ClassDraftTile extends StatelessWidget {
  const _ClassDraftTile({required this.row});

  final _DraftClassRow row;

  @override
  Widget build(BuildContext context) {
    return StatefulBuilder(
      builder: (context, setRowState) {
        final classEntity = row.classEntity;
        return Card(
          margin: const EdgeInsets.symmetric(vertical: 4),
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Checkbox(
                      value: row.included,
                      onChanged: (value) => setRowState(() => row.included = value ?? true),
                    ),
                    Expanded(
                      child: TextField(
                        controller: row.nameController,
                        enabled: row.included,
                        decoration: const InputDecoration(isDense: true, border: OutlineInputBorder()),
                      ),
                    ),
                  ],
                ),
                if (classEntity.attributes.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(left: 48, right: 8),
                    child: Text(
                      'Atributos: ${classEntity.attributes.map((a) => '${a.name}: ${attributeTypeLabel(a.type)}').join(', ')}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
                if (classEntity.methods.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(left: 48, right: 8, bottom: 4),
                    child: Text(
                      'Métodos: ${classEntity.methods.map((m) => '${m.name}()').join(', ')}',
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ),
              ],
            ),
          ),
        );
      },
    );
  }
}
