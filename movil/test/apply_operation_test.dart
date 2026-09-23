import 'package:flutter_test/flutter_test.dart';
import 'package:modelcollab_mobile/features/diagram/apply_operation.dart';
import 'package:modelcollab_mobile/features/diagram/diagram_models.dart';

Map<String, dynamic> _classPayload(String id, {String name = 'Cliente'}) => {
      'id': id,
      'name': name,
      'visibility': 'PUBLIC',
      'isAbstract': false,
      'position': {'x': 10, 'y': 20},
      'width': 240,
      'height': 180,
      'attributes': [],
      'methods': [],
    };

Map<String, dynamic> _attributePayload(String id, {String name = 'email', bool isPrimaryKey = false}) => {
      'id': id,
      'name': name,
      'type': 'VARCHAR',
      'length': 255,
      'precision': 10,
      'scale': 2,
      'visibility': 'PRIVATE',
      'isPrimaryKey': isPrimaryKey,
      'isNullable': true,
      'isUnique': false,
      'defaultValue': null,
    };

Map<String, dynamic> _relationshipPayload(String id, String sourceId, String targetId) => {
      'id': id,
      'sourceClassId': sourceId,
      'targetClassId': targetId,
      'type': 'ASSOCIATION',
      'owningSide': 'SOURCE',
      'joinTableName': null,
      'sourceMultiplicity': '0..1',
      'targetMultiplicity': '0..*',
      'sourceRole': null,
      'targetRole': null,
      'isNavigable': true,
      'waypoints': [],
    };

void main() {
  group('applyOperation', () {
    test('ADD_CLASS agrega una clase nueva y es idempotente por id', () {
      final withClass = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      expect(withClass.classes, hasLength(1));
      expect(withClass.classes.single.name, 'Cliente');

      final appliedTwice = applyOperation(withClass, 'ADD_CLASS', null, _classPayload('c1', name: 'Otro'));
      expect(appliedTwice.classes, hasLength(1));
      expect(appliedTwice.classes.single.name, 'Cliente', reason: 'no debe pisar la clase existente con el mismo id');
    });

    test('MOVE_CLASS actualiza solo la posición de la clase indicada', () {
      final model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      final moved = applyOperation(model, 'MOVE_CLASS', 'c1', {'x': 100.0, 'y': 200.0});

      expect(moved.classes.single.position.x, 100.0);
      expect(moved.classes.single.position.y, 200.0);
      expect(moved.classes.single.name, 'Cliente', reason: 'MOVE_CLASS no debe tocar otros campos');
    });

    test('RENAME_CLASS cambia el nombre sin afectar el resto', () {
      final model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      final renamed = applyOperation(model, 'RENAME_CLASS', 'c1', {'name': 'Usuario'});

      expect(renamed.classes.single.name, 'Usuario');
      expect(renamed.classes.single.id, 'c1');
    });

    test('DELETE_CLASS elimina la clase y las relaciones que la referencian', () {
      var model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      model = applyOperation(model, 'ADD_CLASS', null, _classPayload('c2', name: 'Pedido'));
      model = applyOperation(model, 'ADD_RELATIONSHIP', null, _relationshipPayload('r1', 'c1', 'c2'));
      expect(model.relationships, hasLength(1));

      final afterDelete = applyOperation(model, 'DELETE_CLASS', 'c1', const {});

      expect(afterDelete.classes.map((c) => c.id), ['c2']);
      expect(afterDelete.relationships, isEmpty, reason: 'la relación quedó huérfana y debe limpiarse');
    });

    test('ADD_ATTRIBUTE agrega el atributo a la clase destino, sin duplicar por id', () {
      final model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      final withAttr = applyOperation(model, 'ADD_ATTRIBUTE', 'c1', _attributePayload('a1'));

      expect(withAttr.classes.single.attributes, hasLength(1));

      final again = applyOperation(withAttr, 'ADD_ATTRIBUTE', 'c1', _attributePayload('a1', name: 'otro'));
      expect(again.classes.single.attributes, hasLength(1));
      expect(again.classes.single.attributes.single.name, 'email');
    });

    test('UPDATE_ATTRIBUTE mergea el payload parcial preservando lo no incluido', () {
      var model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      model = applyOperation(model, 'ADD_ATTRIBUTE', 'c1', _attributePayload('a1'));

      final updated = applyOperation(model, 'UPDATE_ATTRIBUTE', 'a1', {'isPrimaryKey': true});

      final attribute = updated.classes.single.attributes.single;
      expect(attribute.isPrimaryKey, isTrue);
      expect(attribute.name, 'email', reason: 'un campo no incluido en el payload debe preservarse');
    });

    test('DELETE_ATTRIBUTE quita el atributo de la clase que lo contiene', () {
      var model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      model = applyOperation(model, 'ADD_ATTRIBUTE', 'c1', _attributePayload('a1'));

      final afterDelete = applyOperation(model, 'DELETE_ATTRIBUTE', 'a1', const {});

      expect(afterDelete.classes.single.attributes, isEmpty);
    });

    test('ADD_RELATIONSHIP agrega la relación una sola vez por id', () {
      var model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      model = applyOperation(model, 'ADD_CLASS', null, _classPayload('c2', name: 'Pedido'));

      final withRel = applyOperation(model, 'ADD_RELATIONSHIP', null, _relationshipPayload('r1', 'c1', 'c2'));
      expect(withRel.relationships, hasLength(1));

      final again = applyOperation(withRel, 'ADD_RELATIONSHIP', null, _relationshipPayload('r1', 'c1', 'c2'));
      expect(again.relationships, hasLength(1));
    });

    test('UPDATE_WAYPOINTS reemplaza los waypoints de la relación indicada', () {
      var model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      model = applyOperation(model, 'ADD_CLASS', null, _classPayload('c2', name: 'Pedido'));
      model = applyOperation(model, 'ADD_RELATIONSHIP', null, _relationshipPayload('r1', 'c1', 'c2'));

      final updated = applyOperation(model, 'UPDATE_WAYPOINTS', 'r1', {
        'waypoints': [
          {'x': 1.0, 'y': 2.0},
          {'x': 3.0, 'y': 4.0},
        ],
      });

      expect(updated.relationships.single.waypoints, hasLength(2));
      expect(updated.relationships.single.waypoints.last.x, 3.0);
    });

    test('DELETE_RELATIONSHIP elimina solo la relación indicada', () {
      var model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      model = applyOperation(model, 'ADD_CLASS', null, _classPayload('c2', name: 'Pedido'));
      model = applyOperation(model, 'ADD_RELATIONSHIP', null, _relationshipPayload('r1', 'c1', 'c2'));

      final afterDelete = applyOperation(model, 'DELETE_RELATIONSHIP', 'r1', const {});

      expect(afterDelete.relationships, isEmpty);
    });

    test('una operación desconocida (fuera de alcance) no rompe y deja el modelo intacto', () {
      final model = applyOperation(CanonicalModel.empty, 'ADD_CLASS', null, _classPayload('c1'));
      final untouched = applyOperation(model, 'USER_CURSOR', null, {'x': 1, 'y': 2});

      expect(untouched.classes, hasLength(1));
      expect(identical(untouched.relationships, model.relationships), isTrue);
    });
  });

  group('CanonicalModel.fromJson', () {
    test('parsea un snapshot completo (clases + relaciones)', () {
      final json = {
        'schemaVersion': '1.0.0',
        'mutationVersion': 3,
        'packages': [],
        'classes': [_classPayload('c1'), _classPayload('c2', name: 'Pedido')],
        'relationships': [_relationshipPayload('r1', 'c1', 'c2')],
      };

      final model = CanonicalModel.fromJson(json);

      expect(model.mutationVersion, 3);
      expect(model.classes, hasLength(2));
      expect(model.relationships, hasLength(1));
      expect(model.relationships.single.sourceMultiplicity, '0..1');
    });

    test('un snapshot vacío (diagrama recién creado) no rompe', () {
      final model = CanonicalModel.fromJson(const {
        'schemaVersion': '1.0.0',
        'mutationVersion': 1,
        'packages': [],
        'classes': [],
        'relationships': [],
      });

      expect(model.classes, isEmpty);
      expect(model.relationships, isEmpty);
    });
  });
}
