import '../diagram/diagram_models.dart';

/// Filtra qué relaciones del borrador corresponden enviar como
/// ADD_RELATIONSHIP al confirmar (CU12).
///
/// A diferencia de `VisionModal.tsx` (que solo arma `includedClassIds` con
/// las clases del propio borrador, así que una relación hacia una clase YA
/// EXISTENTE del diagrama —`matchedExistingClassIds`— quedaría descartada),
/// acá se unen ambos conjuntos: una relación hacia una clase que ya existe
/// en el diagrama es válida y no debería perderse solo porque esa clase no
/// viene en `classes` (el borrador nunca la incluye ahí, justamente porque
/// ya existe — ver el javadoc de `DraftModelResponse.matchedExistingClassIds`).
List<Relationship> selectRelationshipsToApply({
  required List<ClassEntity> includedClasses,
  required List<String> matchedExistingClassIds,
  required List<Relationship> relationships,
  required bool Function(Relationship) isIncluded,
}) {
  final includedClassIds = <String>{
    ...includedClasses.map((c) => c.id),
    ...matchedExistingClassIds,
  };

  return relationships
      .where(isIncluded)
      .where((r) => includedClassIds.contains(r.sourceClassId) && includedClassIds.contains(r.targetClassId))
      .toList();
}
