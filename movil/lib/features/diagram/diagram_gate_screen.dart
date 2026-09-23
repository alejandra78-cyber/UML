import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../auth/auth_controller.dart';
import 'diagram_models.dart';
import 'diagram_repository.dart';
import 'diagram_view_screen.dart';
import 'project_selector_screen.dart';

/// Punto de entrada a Fase 2: intenta reabrir el último diagrama activo de
/// este usuario (cacheado en [DiagramRepository]); si no hay nada cacheado o
/// el diagrama cacheado ya no es accesible (404/403 al pedir el snapshot),
/// cae al selector de proyecto explícito.
class DiagramGateScreen extends StatefulWidget {
  const DiagramGateScreen({super.key});

  @override
  State<DiagramGateScreen> createState() => _DiagramGateScreenState();
}

class _DiagramGateScreenState extends State<DiagramGateScreen> {
  final _repository = const DiagramRepository();

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  Future<void> _resolve() async {
    final userId = AuthController.instance.session.value?.userId;
    if (userId == null) {
      _goToSelector();
      return;
    }

    final cached = await _repository.readCachedActiveDiagram(userId);
    if (cached == null) {
      _goToSelector();
      return;
    }

    try {
      final model = await _repository.fetchSnapshot(cached.diagramId);
      _goToDiagram(cached.diagramId, model);
    } on ApiException {
      await _repository.clearCachedActiveDiagram(userId);
      _goToSelector();
    }
  }

  void _goToSelector() {
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => const ProjectSelectorScreen()),
    );
  }

  void _goToDiagram(String diagramId, CanonicalModel model) {
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => DiagramViewScreen(diagramId: diagramId, initialModel: model)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return const Scaffold(body: Center(child: CircularProgressIndicator()));
  }
}
