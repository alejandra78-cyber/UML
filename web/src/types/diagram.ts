// Tipos alineados al JSON Schema canónico de la sección 7 del PLAN_ARQUITECTONICO.md

export type Visibility = 'PUBLIC' | 'PACKAGE' | 'PROTECTED' | 'PRIVATE'

export type AttributeType =
  | 'INTEGER'
  | 'BIGINT'
  | 'VARCHAR'
  | 'TEXT'
  | 'DECIMAL'
  | 'BOOLEAN'
  | 'DATE'
  | 'DATETIME'
  | 'UUID'

export interface Position {
  x: number
  y: number
}

export interface Attribute {
  id: string
  name: string
  type: AttributeType
  visibility: Visibility
  length?: number
  precision?: number
  scale?: number
  isPrimaryKey?: boolean
  isNullable?: boolean
  isUnique?: boolean
  defaultValue?: string | null
}

export interface Parameter {
  name: string
  type: string
}

export interface Method {
  id: string
  name: string
  returnType: string
  visibility: Visibility
  parameters: Parameter[]
}

export interface ClassEntity {
  id: string
  name: string
  visibility?: Visibility
  isAbstract?: boolean
  position: Position
  width: number
  height: number
  attributes: Attribute[]
  methods: Method[]
}

// Los primeros 6 son exactamente los tipos de relación de diagrama de clases del
// estándar OMG UML 2.5.1 (diciembre 2017, capítulo de Class Diagrams): ASSOCIATION,
// AGGREGATION, COMPOSITION, GENERALIZATION, DEPENDENCY, REALIZATION. MANY_TO_MANY
// NO es un tipo UML (una asociación N:M se modela en UML como ASSOCIATION con
// multiplicidad 0..* en ambos extremos); se conserva solo para la generación de
// tabla intermedia JPA en el backend (ver RelationshipType.java) y nunca se ofrece
// como opción de notación en el selector de tipo de UmlRelationshipEdge.
export type RelationshipType =
  | 'ASSOCIATION'
  | 'AGGREGATION'
  | 'COMPOSITION'
  | 'GENERALIZATION'
  | 'DEPENDENCY'
  | 'REALIZATION'
  | 'MANY_TO_MANY'

export type Multiplicity = '0..1' | '1..1' | '0..*' | '1..*'

export type OwningSide = 'SOURCE' | 'TARGET'

export interface Waypoint {
  x: number
  y: number
}

export interface Relationship {
  id: string
  sourceClassId: string
  targetClassId: string
  type: RelationshipType
  // El backend deserializa Relationship como record de Java: owningSide, isNavigable
  // y waypoints son parámetros del constructor (no tienen default si faltan del JSON),
  // así que SIEMPRE hay que enviarlos al crear una relación nueva (ver
  // useDiagramStore.addRelationship) aunque acá queden opcionales para permitir un
  // merge parcial en updateRelationship.
  owningSide?: OwningSide
  joinTableName?: string | null
  sourceMultiplicity?: Multiplicity
  targetMultiplicity?: Multiplicity
  sourceRole?: string
  targetRole?: string
  isNavigable?: boolean
  waypoints?: Waypoint[]
}

export interface CanonicalModel {
  schemaVersion: string
  mutationVersion: number
  classes: ClassEntity[]
  relationships: Relationship[]
}
