import '../diagram/diagram_models.dart';

/// Espejo de `com.modelcollab.vision.dto.DraftModelResponse` (backend, CU12).
/// Este endpoint NUNCA aplica nada sobre el diagrama persistido — es un
/// borrador Human-in-the-Loop para revisar antes de confirmar.
class DraftModelResponse {
  const DraftModelResponse({
    required this.diagramId,
    required this.classes,
    required this.relationships,
    required this.matchedExistingClassIds,
    required this.warnings,
  });

  final String diagramId;
  final List<ClassEntity> classes;
  final List<Relationship> relationships;
  final List<String> matchedExistingClassIds;
  final List<String> warnings;

  factory DraftModelResponse.fromJson(Map<String, dynamic> json) => DraftModelResponse(
        diagramId: json['diagramId'] as String,
        classes: (json['classes'] as List<dynamic>? ?? [])
            .map((c) => ClassEntity.fromJson(c as Map<String, dynamic>))
            .toList(),
        relationships: (json['relationships'] as List<dynamic>? ?? [])
            .map((r) => Relationship.fromJson(r as Map<String, dynamic>))
            .toList(),
        matchedExistingClassIds: (json['matchedExistingClassIds'] as List<dynamic>? ?? []).cast<String>(),
        warnings: (json['warnings'] as List<dynamic>? ?? []).cast<String>(),
      );
}
