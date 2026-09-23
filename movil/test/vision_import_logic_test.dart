import 'package:flutter_test/flutter_test.dart';
import 'package:modelcollab_mobile/features/diagram/diagram_models.dart';
import 'package:modelcollab_mobile/features/vision_import/vision_import_logic.dart';

ClassEntity _classEntity(String id, {String name = 'Clase'}) => ClassEntity(
      id: id,
      name: name,
      visibility: ClassVisibility.public,
      isAbstract: false,
      position: const Position(x: 0, y: 0),
      width: 240,
      height: 180,
      attributes: const [],
      methods: const [],
    );

Relationship _relationship(String id, String sourceId, String targetId) => Relationship(
      id: id,
      sourceClassId: sourceId,
      targetClassId: targetId,
      type: RelationshipType.association,
      owningSide: 'SOURCE',
      isNavigable: true,
      waypoints: const [],
    );

void main() {
  group('selectRelationshipsToApply', () {
    test('incluye una relación cuyos dos extremos son clases nuevas incluidas', () {
      final result = selectRelationshipsToApply(
        includedClasses: [_classEntity('c1'), _classEntity('c2')],
        matchedExistingClassIds: const [],
        relationships: [_relationship('r1', 'c1', 'c2')],
        isIncluded: (_) => true,
      );

      expect(result.map((r) => r.id), ['r1']);
    });

    test('descarta una relación marcada como no incluida por el usuario', () {
      final result = selectRelationshipsToApply(
        includedClasses: [_classEntity('c1'), _classEntity('c2')],
        matchedExistingClassIds: const [],
        relationships: [_relationship('r1', 'c1', 'c2')],
        isIncluded: (_) => false,
      );

      expect(result, isEmpty);
    });

    test('descarta una relación cuya clase de origen quedó desmarcada', () {
      final result = selectRelationshipsToApply(
        includedClasses: [_classEntity('c2')],
        matchedExistingClassIds: const [],
        relationships: [_relationship('r1', 'c1', 'c2')],
        isIncluded: (_) => true,
      );

      expect(result, isEmpty);
    });

    test('incluye una relación hacia una clase YA EXISTENTE (matchedExistingClassIds), '
        'no solo hacia clases nuevas del borrador', () {
      final result = selectRelationshipsToApply(
        includedClasses: [_classEntity('c1')],
        matchedExistingClassIds: const ['existing-1'],
        relationships: [_relationship('r1', 'c1', 'existing-1')],
        isIncluded: (_) => true,
      );

      expect(result.map((r) => r.id), ['r1']);
    });
  });
}
