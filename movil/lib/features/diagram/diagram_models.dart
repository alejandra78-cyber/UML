/// Espejo en Dart de `com.modelcollab.metamodel.model.*` (backend) y de
/// `web/src/types/diagram.ts` — mismo esquema canónico (sección 7 del
/// documento de arquitectura). Solo lo necesario para RENDERIZAR el modelo
/// (Fase 2: visor de solo lectura); no se envía nada de esto de vuelta al
/// backend todavía.
library;

enum ClassVisibility { public, protected, package, private }

ClassVisibility visibilityFromJson(String? value) {
  switch (value) {
    case 'PROTECTED':
      return ClassVisibility.protected;
    case 'PACKAGE':
      return ClassVisibility.package;
    case 'PRIVATE':
      return ClassVisibility.private;
    case 'PUBLIC':
    default:
      return ClassVisibility.public;
  }
}

String visibilityToJson(ClassVisibility visibility) {
  switch (visibility) {
    case ClassVisibility.public:
      return 'PUBLIC';
    case ClassVisibility.protected:
      return 'PROTECTED';
    case ClassVisibility.package:
      return 'PACKAGE';
    case ClassVisibility.private:
      return 'PRIVATE';
  }
}

String visibilitySymbol(ClassVisibility visibility) {
  switch (visibility) {
    case ClassVisibility.public:
      return '+';
    case ClassVisibility.protected:
      return '#';
    case ClassVisibility.package:
      return '~';
    case ClassVisibility.private:
      return '-';
  }
}

enum AttributeType { integer, bigint, varchar, text, decimal, boolean, date, datetime, uuid }

AttributeType attributeTypeFromJson(String? value) {
  switch (value) {
    case 'BIGINT':
      return AttributeType.bigint;
    case 'TEXT':
      return AttributeType.text;
    case 'DECIMAL':
      return AttributeType.decimal;
    case 'BOOLEAN':
      return AttributeType.boolean;
    case 'DATE':
      return AttributeType.date;
    case 'DATETIME':
      return AttributeType.datetime;
    case 'UUID':
      return AttributeType.uuid;
    case 'VARCHAR':
      return AttributeType.varchar;
    case 'INTEGER':
    default:
      return AttributeType.integer;
  }
}

String attributeTypeLabel(AttributeType type) => type.name.toUpperCase();

String attributeTypeToJson(AttributeType type) => type.name.toUpperCase();

enum RelationshipType {
  association,
  aggregation,
  composition,
  generalization,
  dependency,
  realization,
  manyToMany,
}

RelationshipType relationshipTypeFromJson(String? value) {
  switch (value) {
    case 'AGGREGATION':
      return RelationshipType.aggregation;
    case 'COMPOSITION':
      return RelationshipType.composition;
    case 'GENERALIZATION':
      return RelationshipType.generalization;
    case 'DEPENDENCY':
      return RelationshipType.dependency;
    case 'REALIZATION':
      return RelationshipType.realization;
    case 'MANY_TO_MANY':
      return RelationshipType.manyToMany;
    case 'ASSOCIATION':
    default:
      return RelationshipType.association;
  }
}

String relationshipTypeLabel(RelationshipType type) {
  switch (type) {
    case RelationshipType.association:
      return 'Asociación';
    case RelationshipType.aggregation:
      return 'Agregación';
    case RelationshipType.composition:
      return 'Composición';
    case RelationshipType.generalization:
      return 'Generalización';
    case RelationshipType.dependency:
      return 'Dependencia';
    case RelationshipType.realization:
      return 'Realización';
    case RelationshipType.manyToMany:
      return 'Muchos a muchos';
  }
}

String relationshipTypeToJson(RelationshipType type) {
  switch (type) {
    case RelationshipType.association:
      return 'ASSOCIATION';
    case RelationshipType.aggregation:
      return 'AGGREGATION';
    case RelationshipType.composition:
      return 'COMPOSITION';
    case RelationshipType.generalization:
      return 'GENERALIZATION';
    case RelationshipType.dependency:
      return 'DEPENDENCY';
    case RelationshipType.realization:
      return 'REALIZATION';
    case RelationshipType.manyToMany:
      return 'MANY_TO_MANY';
  }
}

/// Literal textual ("0..1", "1..*", ...), no el nombre del enum: el backend
/// serializa `Multiplicity` vía `@JsonValue` directamente contra esa literal.
String multiplicityLabel(String? literal) => literal ?? '';

class Position {
  const Position({required this.x, required this.y});

  final double x;
  final double y;

  factory Position.fromJson(Map<String, dynamic> json) => Position(
        x: (json['x'] as num?)?.toDouble() ?? 0,
        y: (json['y'] as num?)?.toDouble() ?? 0,
      );

  Map<String, dynamic> toJson() => {'x': x, 'y': y};
}

class Waypoint {
  const Waypoint({required this.x, required this.y});

  final double x;
  final double y;

  factory Waypoint.fromJson(Map<String, dynamic> json) => Waypoint(
        x: (json['x'] as num?)?.toDouble() ?? 0,
        y: (json['y'] as num?)?.toDouble() ?? 0,
      );

  Map<String, dynamic> toJson() => {'x': x, 'y': y};
}

class Attribute {
  const Attribute({
    required this.id,
    required this.name,
    required this.type,
    required this.visibility,
    required this.isPrimaryKey,
    required this.isNullable,
    required this.isUnique,
    this.defaultValue,
  });

  final String id;
  final String name;
  final AttributeType type;
  final ClassVisibility visibility;
  final bool isPrimaryKey;
  final bool isNullable;
  final bool isUnique;
  final String? defaultValue;

  factory Attribute.fromJson(Map<String, dynamic> json) => Attribute(
        id: json['id'] as String,
        name: json['name'] as String,
        type: attributeTypeFromJson(json['type'] as String?),
        visibility: visibilityFromJson(json['visibility'] as String?),
        isPrimaryKey: json['isPrimaryKey'] as bool? ?? false,
        isNullable: json['isNullable'] as bool? ?? true,
        isUnique: json['isUnique'] as bool? ?? false,
        defaultValue: json['defaultValue'] as String?,
      );

  /// Aplica un payload parcial (UPDATE_ATTRIBUTE) preservando lo no incluido,
  /// igual que el spread `{...a, ...payload}` de applyOperation.ts.
  Attribute mergeJson(Map<String, dynamic> payload) => Attribute(
        id: id,
        name: payload['name'] as String? ?? name,
        type: payload.containsKey('type') ? attributeTypeFromJson(payload['type'] as String?) : type,
        visibility: payload.containsKey('visibility')
            ? visibilityFromJson(payload['visibility'] as String?)
            : visibility,
        isPrimaryKey: payload['isPrimaryKey'] as bool? ?? isPrimaryKey,
        isNullable: payload['isNullable'] as bool? ?? isNullable,
        isUnique: payload['isUnique'] as bool? ?? isUnique,
        defaultValue: payload.containsKey('defaultValue') ? payload['defaultValue'] as String? : defaultValue,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'type': attributeTypeToJson(type),
        'visibility': visibilityToJson(visibility),
        'isPrimaryKey': isPrimaryKey,
        'isNullable': isNullable,
        'isUnique': isUnique,
        'defaultValue': defaultValue,
      };
}

class Parameter {
  const Parameter({required this.name, required this.type});

  final String name;
  final String type;

  factory Parameter.fromJson(Map<String, dynamic> json) => Parameter(
        name: json['name'] as String? ?? '',
        type: json['type'] as String? ?? '',
      );

  Map<String, dynamic> toJson() => {'name': name, 'type': type};
}

class Method {
  const Method({
    required this.id,
    required this.name,
    required this.returnType,
    required this.visibility,
    required this.parameters,
  });

  final String id;
  final String name;
  final String returnType;
  final ClassVisibility visibility;
  final List<Parameter> parameters;

  factory Method.fromJson(Map<String, dynamic> json) => Method(
        id: json['id'] as String,
        name: json['name'] as String,
        returnType: json['returnType'] as String? ?? 'void',
        visibility: visibilityFromJson(json['visibility'] as String?),
        parameters: (json['parameters'] as List<dynamic>? ?? [])
            .map((p) => Parameter.fromJson(p as Map<String, dynamic>))
            .toList(),
      );

  Method mergeJson(Map<String, dynamic> payload) => Method(
        id: id,
        name: payload['name'] as String? ?? name,
        returnType: payload['returnType'] as String? ?? returnType,
        visibility: payload.containsKey('visibility')
            ? visibilityFromJson(payload['visibility'] as String?)
            : visibility,
        parameters: payload.containsKey('parameters')
            ? (payload['parameters'] as List<dynamic>? ?? [])
                .map((p) => Parameter.fromJson(p as Map<String, dynamic>))
                .toList()
            : parameters,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'returnType': returnType,
        'visibility': visibilityToJson(visibility),
        'parameters': parameters.map((p) => p.toJson()).toList(),
      };
}

class ClassEntity {
  const ClassEntity({
    required this.id,
    required this.name,
    required this.visibility,
    required this.isAbstract,
    required this.position,
    required this.width,
    required this.height,
    required this.attributes,
    required this.methods,
  });

  final String id;
  final String name;
  final ClassVisibility visibility;
  final bool isAbstract;
  final Position position;
  final double width;
  final double height;
  final List<Attribute> attributes;
  final List<Method> methods;

  factory ClassEntity.fromJson(Map<String, dynamic> json) => ClassEntity(
        id: json['id'] as String,
        name: json['name'] as String,
        visibility: visibilityFromJson(json['visibility'] as String?),
        isAbstract: json['isAbstract'] as bool? ?? false,
        position: Position.fromJson(json['position'] as Map<String, dynamic>? ?? const {}),
        width: (json['width'] as num?)?.toDouble() ?? 240,
        height: (json['height'] as num?)?.toDouble() ?? 180,
        attributes: (json['attributes'] as List<dynamic>? ?? [])
            .map((a) => Attribute.fromJson(a as Map<String, dynamic>))
            .toList(),
        methods: (json['methods'] as List<dynamic>? ?? [])
            .map((m) => Method.fromJson(m as Map<String, dynamic>))
            .toList(),
      );

  ClassEntity copyWith({
    String? name,
    Position? position,
    double? width,
    double? height,
    List<Attribute>? attributes,
    List<Method>? methods,
  }) =>
      ClassEntity(
        id: id,
        name: name ?? this.name,
        visibility: visibility,
        isAbstract: isAbstract,
        position: position ?? this.position,
        width: width ?? this.width,
        height: height ?? this.height,
        attributes: attributes ?? this.attributes,
        methods: methods ?? this.methods,
      );

  /// Igual forma que espera `ADD_CLASS`/`BULK_MERGE` del lado del backend
  /// (`CanonicalModelMutator`/`applyOperation.ts` del frontend web).
  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'visibility': visibilityToJson(visibility),
        'isAbstract': isAbstract,
        'position': position.toJson(),
        'width': width,
        'height': height,
        'attributes': attributes.map((a) => a.toJson()).toList(),
        'methods': methods.map((m) => m.toJson()).toList(),
      };
}

class Relationship {
  const Relationship({
    required this.id,
    required this.sourceClassId,
    required this.targetClassId,
    required this.type,
    required this.owningSide,
    this.joinTableName,
    this.sourceMultiplicity,
    this.targetMultiplicity,
    this.sourceRole,
    this.targetRole,
    required this.isNavigable,
    required this.waypoints,
  });

  final String id;
  final String sourceClassId;
  final String targetClassId;
  final RelationshipType type;
  final String owningSide;
  final String? joinTableName;
  final String? sourceMultiplicity;
  final String? targetMultiplicity;
  final String? sourceRole;
  final String? targetRole;
  final bool isNavigable;
  final List<Waypoint> waypoints;

  factory Relationship.fromJson(Map<String, dynamic> json) => Relationship(
        id: json['id'] as String,
        sourceClassId: json['sourceClassId'] as String,
        targetClassId: json['targetClassId'] as String,
        type: relationshipTypeFromJson(json['type'] as String?),
        owningSide: json['owningSide'] as String? ?? 'SOURCE',
        joinTableName: json['joinTableName'] as String?,
        sourceMultiplicity: json['sourceMultiplicity'] as String?,
        targetMultiplicity: json['targetMultiplicity'] as String?,
        sourceRole: json['sourceRole'] as String?,
        targetRole: json['targetRole'] as String?,
        isNavigable: json['isNavigable'] as bool? ?? true,
        waypoints: (json['waypoints'] as List<dynamic>? ?? [])
            .map((w) => Waypoint.fromJson(w as Map<String, dynamic>))
            .toList(),
      );

  Relationship copyWith({List<Waypoint>? waypoints}) => Relationship(
        id: id,
        sourceClassId: sourceClassId,
        targetClassId: targetClassId,
        type: type,
        owningSide: owningSide,
        joinTableName: joinTableName,
        sourceMultiplicity: sourceMultiplicity,
        targetMultiplicity: targetMultiplicity,
        sourceRole: sourceRole,
        targetRole: targetRole,
        isNavigable: isNavigable,
        waypoints: waypoints ?? this.waypoints,
      );

  /// UPDATE_RELATIONSHIP: solo type/owningSide/joinTableName/multiplicidades/
  /// roles/isNavigable son mutables — id, sourceClassId, targetClassId y
  /// waypoints se preservan siempre (mismo contrato que
  /// CanonicalModelMutator.updateRelationship en el backend).
  Relationship mergeJson(Map<String, dynamic> payload) => Relationship(
        id: id,
        sourceClassId: sourceClassId,
        targetClassId: targetClassId,
        type: payload.containsKey('type') ? relationshipTypeFromJson(payload['type'] as String?) : type,
        owningSide: payload['owningSide'] as String? ?? owningSide,
        joinTableName: payload.containsKey('joinTableName') ? payload['joinTableName'] as String? : joinTableName,
        sourceMultiplicity: payload['sourceMultiplicity'] as String? ?? sourceMultiplicity,
        targetMultiplicity: payload['targetMultiplicity'] as String? ?? targetMultiplicity,
        sourceRole: payload.containsKey('sourceRole') ? payload['sourceRole'] as String? : sourceRole,
        targetRole: payload.containsKey('targetRole') ? payload['targetRole'] as String? : targetRole,
        isNavigable: payload['isNavigable'] as bool? ?? isNavigable,
        waypoints: waypoints,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'sourceClassId': sourceClassId,
        'targetClassId': targetClassId,
        'type': relationshipTypeToJson(type),
        'owningSide': owningSide,
        'joinTableName': joinTableName,
        'sourceMultiplicity': sourceMultiplicity,
        'targetMultiplicity': targetMultiplicity,
        'sourceRole': sourceRole,
        'targetRole': targetRole,
        'isNavigable': isNavigable,
        'waypoints': waypoints.map((w) => w.toJson()).toList(),
      };

  /// Copia con nuevo `id` y `sourceClassId`/`targetClassId` — usado al
  /// confirmar un borrador de importación por foto (CU12): el id propuesto
  /// por el backend se descarta y se genera uno nuevo del lado del cliente,
  /// mismo criterio que `addRelationship` en el store web.
  Relationship withNewId(String newId) => Relationship(
        id: newId,
        sourceClassId: sourceClassId,
        targetClassId: targetClassId,
        type: type,
        owningSide: owningSide,
        joinTableName: joinTableName,
        sourceMultiplicity: sourceMultiplicity,
        targetMultiplicity: targetMultiplicity,
        sourceRole: sourceRole,
        targetRole: targetRole,
        isNavigable: isNavigable,
        waypoints: const [],
      );
}

class CanonicalModel {
  const CanonicalModel({
    required this.schemaVersion,
    required this.mutationVersion,
    required this.classes,
    required this.relationships,
  });

  final String schemaVersion;
  final int mutationVersion;
  final List<ClassEntity> classes;
  final List<Relationship> relationships;

  static const empty = CanonicalModel(
    schemaVersion: '1.0.0',
    mutationVersion: 1,
    classes: [],
    relationships: [],
  );

  /// [currentState] llega como un JSON *ya decodificado* (el backend lo
  /// serializa como string dentro de `SnapshotResponse.currentState`; el
  /// caller debe hacer `jsonDecode` antes de llamar acá).
  factory CanonicalModel.fromJson(Map<String, dynamic> json) => CanonicalModel(
        schemaVersion: json['schemaVersion'] as String? ?? '1.0.0',
        mutationVersion: (json['mutationVersion'] as num?)?.toInt() ?? 1,
        // Los paquetes quedan fuera de alcance de este visor de solo lectura,
        // igual que en el Fase 1 del store web (ver applyOperation.ts).
        classes: (json['classes'] as List<dynamic>? ?? [])
            .map((c) => ClassEntity.fromJson(c as Map<String, dynamic>))
            .toList(),
        relationships: (json['relationships'] as List<dynamic>? ?? [])
            .map((r) => Relationship.fromJson(r as Map<String, dynamic>))
            .toList(),
      );

  CanonicalModel copyWith({
    List<ClassEntity>? classes,
    List<Relationship>? relationships,
  }) =>
      CanonicalModel(
        schemaVersion: schemaVersion,
        mutationVersion: mutationVersion,
        classes: classes ?? this.classes,
        relationships: relationships ?? this.relationships,
      );
}
