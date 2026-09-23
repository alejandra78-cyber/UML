import 'diagram_models.dart';

/// Espejo en Dart de `web/src/store/applyOperation.ts`, que a su vez espeja
/// `CanonicalModelMutator` (backend): aplica sobre una copia en memoria del
/// [CanonicalModel] el mismo catálogo de operaciones STOMP (sección 8.1),
/// reconstruyendo el estado local a partir de los `StompBroadcastMessage`
/// recibidos en `/topic/diagrams/{id}`. Solo lectura: no envía nada de vuelta.
CanonicalModel applyOperation(
  CanonicalModel model,
  String operationType,
  String? targetId,
  Map<String, dynamic> payload,
) {
  switch (operationType) {
    case 'ADD_CLASS':
      return _addClass(model, payload);
    case 'MOVE_CLASS':
      return _updateClassById(
        model,
        targetId,
        (c) => c.copyWith(
          position: Position(
            x: _numberField(payload, 'x', c.position.x),
            y: _numberField(payload, 'y', c.position.y),
          ),
        ),
      );
    case 'RESIZE_CLASS':
      return _updateClassById(
        model,
        targetId,
        (c) => c.copyWith(
          width: _numberField(payload, 'width', c.width),
          height: _numberField(payload, 'height', c.height),
        ),
      );
    case 'RENAME_CLASS':
      return _updateClassById(
        model,
        targetId,
        (c) => c.copyWith(name: payload['name'] as String? ?? c.name),
      );
    case 'DELETE_CLASS':
      return model.copyWith(
        classes: model.classes.where((c) => c.id != targetId).toList(),
        relationships: model.relationships
            .where((r) => r.sourceClassId != targetId && r.targetClassId != targetId)
            .toList(),
      );
    case 'ADD_ATTRIBUTE':
      return _updateClassById(model, targetId, (c) {
        final newAttribute = Attribute.fromJson(payload);
        if (c.attributes.any((a) => a.id == newAttribute.id)) return c;
        return c.copyWith(attributes: [...c.attributes, newAttribute]);
      });
    case 'UPDATE_ATTRIBUTE':
      return _updateAttributeById(model, targetId, (a) => a.mergeJson(payload));
    case 'DELETE_ATTRIBUTE':
      return model.copyWith(
        classes: model.classes
            .map((c) => c.copyWith(attributes: c.attributes.where((a) => a.id != targetId).toList()))
            .toList(),
      );
    case 'ADD_METHOD':
      return _updateClassById(model, targetId, (c) {
        final newMethod = Method.fromJson(payload);
        if (c.methods.any((m) => m.id == newMethod.id)) return c;
        return c.copyWith(methods: [...c.methods, newMethod]);
      });
    case 'UPDATE_METHOD':
      return _updateMethodById(model, targetId, (m) => m.mergeJson(payload));
    case 'DELETE_METHOD':
      return model.copyWith(
        classes: model.classes
            .map((c) => c.copyWith(methods: c.methods.where((m) => m.id != targetId).toList()))
            .toList(),
      );
    case 'ADD_RELATIONSHIP':
      final newRelationship = Relationship.fromJson(payload);
      if (model.relationships.any((r) => r.id == newRelationship.id)) return model;
      return model.copyWith(relationships: [...model.relationships, newRelationship]);
    case 'DELETE_RELATIONSHIP':
      return model.copyWith(relationships: model.relationships.where((r) => r.id != targetId).toList());
    case 'UPDATE_WAYPOINTS':
      final waypoints = (payload['waypoints'] as List<dynamic>? ?? [])
          .map((w) => Waypoint.fromJson(w as Map<String, dynamic>))
          .toList();
      return model.copyWith(
        relationships:
            model.relationships.map((r) => r.id == targetId ? r.copyWith(waypoints: waypoints) : r).toList(),
      );
    case 'UPDATE_RELATIONSHIP':
      return model.copyWith(
        relationships: model.relationships.map((r) => r.id == targetId ? r.mergeJson(payload) : r).toList(),
      );
    default:
      // ACQUIRE_LOCK/RELEASE_LOCK/paquetes/BULK_MERGE/USER_CURSOR: fuera de
      // alcance de este visor de solo lectura (sin locks, sin paquetes, sin
      // cursores todavía — mismo alcance que la Fase 1 del store web).
      return model;
  }
}

CanonicalModel _addClass(CanonicalModel model, Map<String, dynamic> payload) {
  final newClass = ClassEntity.fromJson(payload);
  if (model.classes.any((c) => c.id == newClass.id)) return model;
  return model.copyWith(classes: [...model.classes, newClass]);
}

CanonicalModel _updateClassById(
  CanonicalModel model,
  String? classId,
  ClassEntity Function(ClassEntity) updater,
) {
  if (classId == null) return model;
  return model.copyWith(
    classes: model.classes.map((c) => c.id == classId ? updater(c) : c).toList(),
  );
}

CanonicalModel _updateAttributeById(
  CanonicalModel model,
  String? attributeId,
  Attribute Function(Attribute) updater,
) {
  if (attributeId == null) return model;
  return model.copyWith(
    classes: model.classes
        .map((c) => c.copyWith(
              attributes: c.attributes.map((a) => a.id == attributeId ? updater(a) : a).toList(),
            ))
        .toList(),
  );
}

CanonicalModel _updateMethodById(
  CanonicalModel model,
  String? methodId,
  Method Function(Method) updater,
) {
  if (methodId == null) return model;
  return model.copyWith(
    classes: model.classes
        .map((c) => c.copyWith(
              methods: c.methods.map((m) => m.id == methodId ? updater(m) : m).toList(),
            ))
        .toList(),
  );
}

double _numberField(Map<String, dynamic> payload, String key, double fallback) {
  final value = payload[key];
  return value is num ? value.toDouble() : fallback;
}
