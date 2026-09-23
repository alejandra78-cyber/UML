import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../auth/auth_controller.dart';
import 'diagram_repository.dart';
import 'diagram_view_screen.dart';

/// Elección explícita de proyecto (mismo criterio que `ProjectSelector.tsx`
/// del frontend web: la app ya NO adivina un proyecto por su cuenta). Al
/// elegir uno, resuelve/crea el primer diagrama disponible y cachea el par
/// proyecto/diagrama para no volver a preguntar en la próxima apertura.
class ProjectSelectorScreen extends StatefulWidget {
  const ProjectSelectorScreen({super.key});

  @override
  State<ProjectSelectorScreen> createState() => _ProjectSelectorScreenState();
}

class _ProjectSelectorScreenState extends State<ProjectSelectorScreen> {
  final _repository = const DiagramRepository();
  late Future<List<ProjectSummary>> _projectsFuture;
  bool _isOpeningProject = false;

  @override
  void initState() {
    super.initState();
    _projectsFuture = _repository.listProjects();
  }

  void _reload() {
    setState(() => _projectsFuture = _repository.listProjects());
  }

  Future<void> _openProject(ProjectSummary project) async {
    setState(() => _isOpeningProject = true);
    try {
      final diagramId = await _repository.resolveDiagramForProject(project.id);
      final userId = AuthController.instance.session.value!.userId;
      await _repository.writeCachedActiveDiagram(
        userId,
        ActiveDiagram(projectId: project.id, diagramId: diagramId),
      );
      if (mounted) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute(builder: (_) => DiagramViewScreen(diagramId: diagramId)),
        );
      }
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
      }
    } finally {
      if (mounted) setState(() => _isOpeningProject = false);
    }
  }

  Future<void> _createProject() async {
    final nameController = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        title: const Text('Nuevo proyecto'),
        content: TextField(
          controller: nameController,
          autofocus: true,
          decoration: const InputDecoration(labelText: 'Nombre'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(nameController.text),
            child: const Text('Crear'),
          ),
        ],
      ),
    );
    if (name == null || name.trim().isEmpty) return;

    try {
      final project = await _repository.createProject(name.trim(), null);
      _reload();
      await _openProject(project);
    } on ApiException catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.message)));
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Elegí un proyecto'),
        actions: [
          IconButton(
            tooltip: 'Nuevo proyecto',
            icon: const Icon(Icons.add),
            onPressed: _isOpeningProject ? null : _createProject,
          ),
        ],
      ),
      body: _isOpeningProject
          ? const Center(child: CircularProgressIndicator())
          : FutureBuilder<List<ProjectSummary>>(
              future: _projectsFuture,
              builder: (context, snapshot) {
                if (snapshot.connectionState != ConnectionState.done) {
                  return const Center(child: CircularProgressIndicator());
                }
                if (snapshot.hasError) {
                  final message =
                      snapshot.error is ApiException ? (snapshot.error as ApiException).message : 'Error inesperado';
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(message, textAlign: TextAlign.center),
                          const SizedBox(height: 12),
                          FilledButton(onPressed: _reload, child: const Text('Reintentar')),
                        ],
                      ),
                    ),
                  );
                }
                final projects = snapshot.data ?? const [];
                if (projects.isEmpty) {
                  return Center(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Text('Todavía no tenés proyectos.'),
                          const SizedBox(height: 12),
                          FilledButton(onPressed: _createProject, child: const Text('Crear el primero')),
                        ],
                      ),
                    ),
                  );
                }
                return ListView.builder(
                  itemCount: projects.length,
                  itemBuilder: (context, index) {
                    final project = projects[index];
                    return ListTile(
                      leading: const Icon(Icons.folder_outlined),
                      title: Text(project.name),
                      subtitle: project.description != null ? Text(project.description!) : null,
                      onTap: () => _openProject(project),
                    );
                  },
                );
              },
            ),
    );
  }
}
