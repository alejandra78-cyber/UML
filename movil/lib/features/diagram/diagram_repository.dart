import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../auth/auth_http_client.dart';
import 'diagram_models.dart';

class ProjectSummary {
  const ProjectSummary({required this.id, required this.name, required this.description});

  final String id;
  final String name;
  final String? description;

  factory ProjectSummary.fromJson(Map<String, dynamic> json) => ProjectSummary(
        id: json['id'] as String,
        name: json['name'] as String,
        description: json['description'] as String?,
      );
}

class DiagramSummary {
  const DiagramSummary({required this.id, required this.projectId, required this.name});

  final String id;
  final String projectId;
  final String name;

  factory DiagramSummary.fromJson(Map<String, dynamic> json) => DiagramSummary(
        id: json['id'] as String,
        projectId: json['projectId'] as String,
        name: json['name'] as String,
      );
}

class ActiveDiagram {
  const ActiveDiagram({required this.projectId, required this.diagramId});

  final String projectId;
  final String diagramId;

  Map<String, dynamic> toJson() => {'projectId': projectId, 'diagramId': diagramId};

  factory ActiveDiagram.fromJson(Map<String, dynamic> json) =>
      ActiveDiagram(projectId: json['projectId'] as String, diagramId: json['diagramId'] as String);
}

/// Acceso a `/api/v1/projects` y `/api/v1/diagrams` (mismo contrato REST que
/// consume `web/src/collaboration/diagramBootstrap.ts`), más el cacheo local
/// del último diagrama activo por usuario. A diferencia de la versión vieja
/// del frontend web (que adivinaba un proyecto "por su cuenta"), acá también
/// la elección de proyecto es siempre explícita del usuario — este cache solo
/// evita pedirla de nuevo en cada apertura, igual que `ProjectSelector.tsx`.
class DiagramRepository {
  const DiagramRepository();

  final AuthHttpClient _http = const AuthHttpClient();

  Future<List<ProjectSummary>> listProjects() async {
    final json = await _http.get('/api/v1/projects') as List<dynamic>;
    return json.map((p) => ProjectSummary.fromJson(p as Map<String, dynamic>)).toList();
  }

  Future<ProjectSummary> createProject(String name, String? description) async {
    final json = await _http.post('/api/v1/projects', {'name': name, 'description': description})
        as Map<String, dynamic>;
    return ProjectSummary.fromJson(json);
  }

  Future<List<DiagramSummary>> listDiagrams(String projectId) async {
    final json = await _http.get('/api/v1/projects/$projectId/diagrams') as List<dynamic>;
    return json.map((d) => DiagramSummary.fromJson(d as Map<String, dynamic>)).toList();
  }

  Future<DiagramSummary> createDiagram(String projectId, String name) async {
    final json =
        await _http.post('/api/v1/projects/$projectId/diagrams', {'name': name}) as Map<String, dynamic>;
    return DiagramSummary.fromJson(json);
  }

  Future<CanonicalModel> fetchSnapshot(String diagramId) async {
    final json = await _http.get('/api/v1/diagrams/$diagramId/snapshot') as Map<String, dynamic>;
    final currentState = jsonDecode(json['currentState'] as String) as Map<String, dynamic>;
    return CanonicalModel.fromJson(currentState);
  }

  /// Dado un proyecto ya elegido por el usuario, resuelve qué diagrama abrir:
  /// reutiliza el primero existente o crea uno vacío si no hay ninguno (mismo
  /// criterio que `resolveDiagramForProject` en el frontend web, para que
  /// varios usuarios del mismo proyecto terminen viendo el mismo diagrama).
  Future<String> resolveDiagramForProject(String projectId) async {
    final diagrams = await listDiagrams(projectId);
    if (diagrams.isNotEmpty) return diagrams.first.id;
    final created = await createDiagram(projectId, 'Diagrama de prueba');
    return created.id;
  }

  static String _cacheKey(String userId) => 'active_diagram_$userId';

  Future<ActiveDiagram?> readCachedActiveDiagram(String userId) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_cacheKey(userId));
    if (raw == null) return null;
    try {
      return ActiveDiagram.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (_) {
      return null;
    }
  }

  Future<void> writeCachedActiveDiagram(String userId, ActiveDiagram active) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_cacheKey(userId), jsonEncode(active.toJson()));
  }

  Future<void> clearCachedActiveDiagram(String userId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_cacheKey(userId));
  }
}
