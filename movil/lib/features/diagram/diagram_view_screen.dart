import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../ai_command/ai_command_bar.dart';
import '../auth/auth_controller.dart';
import '../vision_import/vision_import_screen.dart';
import 'apply_operation.dart';
import 'diagram_models.dart';
import 'diagram_repository.dart';
import 'diagram_stomp_service.dart';
import 'project_selector_screen.dart';

/// Visor de solo lectura (Fase 2): trae el snapshot inicial por REST, se
/// suscribe a `/topic/diagrams/{id}` y va aplicando cada operación entrante
/// sobre el modelo en memoria — no hace falta que sea editable, alcanza con
/// que los cambios de otros clientes se reflejen en vivo acá.
class DiagramViewScreen extends StatefulWidget {
  const DiagramViewScreen({super.key, required this.diagramId, this.initialModel});

  final String diagramId;

  /// Si el caller ya trajo el snapshot (p.ej. para validar un diagrama
  /// cacheado), se lo pasamos para no pedirlo dos veces.
  final CanonicalModel? initialModel;

  @override
  State<DiagramViewScreen> createState() => _DiagramViewScreenState();
}

class _DiagramViewScreenState extends State<DiagramViewScreen> {
  final _repository = const DiagramRepository();
  final _stompService = DiagramStompService();

  CanonicalModel? _model;
  String? _loadError;
  DiagramConnectionStatus _status = DiagramConnectionStatus.connecting;
  String? _statusMessage;
  int _appliedOperations = 0;

  @override
  void initState() {
    super.initState();
    if (widget.initialModel != null) {
      _model = widget.initialModel;
      _connectStomp();
    } else {
      _loadSnapshotAndConnect();
    }
  }

  Future<void> _loadSnapshotAndConnect() async {
    try {
      final model = await _repository.fetchSnapshot(widget.diagramId);
      if (!mounted) return;
      setState(() => _model = model);
      _connectStomp();
    } on ApiException catch (e) {
      if (mounted) setState(() => _loadError = e.message);
    }
  }

  void _connectStomp() {
    final token = AuthController.instance.session.value?.token;
    if (token == null) return;
    _stompService.connect(
      diagramId: widget.diagramId,
      token: token,
      onStatusChange: (status, [message]) {
        if (!mounted) return;
        setState(() {
          _status = status;
          _statusMessage = message;
        });
      },
      onBroadcast: (operationType, targetId, payload) {
        if (!mounted || _model == null) return;
        setState(() {
          _model = applyOperation(_model!, operationType, targetId, payload);
          _appliedOperations++;
        });
      },
    );
  }

  @override
  void dispose() {
    _stompService.disconnect();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Diagrama en vivo'),
        actions: [
          IconButton(
            tooltip: 'Mis proyectos',
            icon: const Icon(Icons.folder_outlined),
            onPressed: () => Navigator.of(context).pushReplacement(
              MaterialPageRoute(builder: (_) => const ProjectSelectorScreen()),
            ),
          ),
          IconButton(
            tooltip: 'Importar foto de pizarra',
            icon: const Icon(Icons.camera_alt_outlined),
            onPressed: _model == null
                ? null
                : () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => VisionImportScreen(
                          diagramId: widget.diagramId,
                          stompService: _stompService,
                        ),
                      ),
                    ),
          ),
        ],
      ),
      body: Column(
        children: [
          _ConnectionBanner(status: _status, message: _statusMessage, appliedOperations: _appliedOperations),
          Expanded(child: _buildBody(context)),
          if (_model != null)
            SafeArea(top: false, child: AiCommandBar(diagramId: widget.diagramId)),
        ],
      ),
    );
  }

  Widget _buildBody(BuildContext context) {
    if (_loadError != null) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(_loadError!, textAlign: TextAlign.center),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () {
                  setState(() => _loadError = null);
                  _loadSnapshotAndConnect();
                },
                child: const Text('Reintentar'),
              ),
            ],
          ),
        ),
      );
    }

    final model = _model;
    if (model == null) {
      return const Center(child: CircularProgressIndicator());
    }
    if (model.classes.isEmpty) {
      return const Center(child: Text('Este diagrama todavía no tiene clases.'));
    }

    return ListView(
      padding: const EdgeInsets.all(12),
      children: [
        ...model.classes.map((c) => _ClassCard(classEntity: c)),
        if (model.relationships.isNotEmpty) ...[
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 8),
            child: Text('Relaciones', style: TextStyle(fontWeight: FontWeight.bold)),
          ),
          ...model.relationships.map((r) => _RelationshipTile(relationship: r, classes: model.classes)),
        ],
      ],
    );
  }
}

class _ConnectionBanner extends StatelessWidget {
  const _ConnectionBanner({required this.status, required this.message, required this.appliedOperations});

  final DiagramConnectionStatus status;
  final String? message;
  final int appliedOperations;

  @override
  Widget build(BuildContext context) {
    final (color, icon, label) = switch (status) {
      DiagramConnectionStatus.connecting => (Colors.orange, Icons.sync, 'Conectando…'),
      DiagramConnectionStatus.connected => (Colors.green, Icons.check_circle, 'Conectado en vivo'),
      DiagramConnectionStatus.reconnecting => (Colors.orange, Icons.sync_problem, 'Reconectando…'),
      DiagramConnectionStatus.error => (Colors.red, Icons.error, 'Error de conexión'),
    };

    return Container(
      width: double.infinity,
      color: color.withValues(alpha: 0.12),
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Row(
        children: [
          Icon(icon, color: color, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              status == DiagramConnectionStatus.connected && appliedOperations > 0
                  ? '$label · $appliedOperations cambio(s) recibido(s)'
                  : (message != null ? '$label: $message' : label),
              style: TextStyle(color: color, fontSize: 13),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

class _ClassCard extends StatelessWidget {
  const _ClassCard({required this.classEntity});

  final ClassEntity classEntity;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    classEntity.isAbstract ? '«abstract» ${classEntity.name}' : classEntity.name,
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const Divider(),
            ...classEntity.attributes.map(
              (a) => Text(
                '${visibilitySymbol(a.visibility)} ${a.name}: ${attributeTypeLabel(a.type)}'
                '${a.isPrimaryKey ? ' (PK)' : ''}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ),
            if (classEntity.methods.isNotEmpty) ...[
              const SizedBox(height: 6),
              ...classEntity.methods.map(
                (m) => Text(
                  '${visibilitySymbol(m.visibility)} ${m.name}(${m.parameters.map((p) => p.type).join(', ')}): ${m.returnType}',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _RelationshipTile extends StatelessWidget {
  const _RelationshipTile({required this.relationship, required this.classes});

  final Relationship relationship;
  final List<ClassEntity> classes;

  String _classNameOf(String id) {
    for (final classEntity in classes) {
      if (classEntity.id == id) return classEntity.name;
    }
    return '?';
  }

  @override
  Widget build(BuildContext context) {
    final sourceName = _classNameOf(relationship.sourceClassId);
    final targetName = _classNameOf(relationship.targetClassId);
    final multiplicities =
        '${multiplicityLabel(relationship.sourceMultiplicity)} → ${multiplicityLabel(relationship.targetMultiplicity)}';
    return ListTile(
      dense: true,
      leading: const Icon(Icons.arrow_right_alt),
      title: Text('$sourceName → $targetName'),
      subtitle: Text('${relationshipTypeLabel(relationship.type)} · $multiplicities'),
    );
  }
}
