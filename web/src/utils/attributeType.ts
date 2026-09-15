import type { Attribute, AttributeType } from '../types/diagram'

// Enum canónico de tipos de atributo (sección 7 del PLAN_ARQUITECTONICO.md). Fuente
// única de verdad para el <select> de tipo en UmlClassNode/ErTableNode.
export const ATTRIBUTE_TYPES: AttributeType[] = [
  'INTEGER',
  'BIGINT',
  'VARCHAR',
  'TEXT',
  'DECIMAL',
  'BOOLEAN',
  'DATE',
  'DATETIME',
  'UUID',
]

// Sinónimos comunes de otros lenguajes/herramientas que un atributo "de pruebas
// anteriores" pudo haber guardado como texto libre antes de existir el <select> de
// tipo. Migración no destructiva: al cargar, se intenta mapear al valor canónico
// más cercano; si no hay match, se conserva el texto original (ver resolveAttributeType).
const TYPE_ALIASES: Record<string, AttributeType> = {
  STRING: 'VARCHAR',
  STR: 'VARCHAR',
  CHAR: 'VARCHAR',
  CHARACTER: 'VARCHAR',
  INT: 'INTEGER',
  INT4: 'INTEGER',
  LONG: 'BIGINT',
  INT8: 'BIGINT',
  FLOAT: 'DECIMAL',
  DOUBLE: 'DECIMAL',
  NUMBER: 'DECIMAL',
  NUMERIC: 'DECIMAL',
  BOOL: 'BOOLEAN',
  TIMESTAMP: 'DATETIME',
  TIME: 'DATETIME',
  GUID: 'UUID',
}

export interface ResolvedAttributeType {
  /** Valor a usar en el <select> -- canónico si se reconoció, o el texto original si no. */
  value: string
  recognized: boolean
}

/** Intenta mapear un tipo (posiblemente texto libre de datos viejos) al enum canónico. */
export function resolveAttributeType(raw: string): ResolvedAttributeType {
  const upper = raw?.toUpperCase().trim() ?? ''
  if ((ATTRIBUTE_TYPES as string[]).includes(upper)) {
    return { value: upper, recognized: true }
  }
  const alias = TYPE_ALIASES[upper]
  if (alias) {
    return { value: alias, recognized: true }
  }
  return { value: raw, recognized: false }
}

/** "VARCHAR(255)" / "DECIMAL(10,2)" / "INTEGER" -- notación con longitud/precisión. */
export function formatAttributeType(attribute: Pick<Attribute, 'type' | 'length' | 'precision' | 'scale'>): string {
  const { type, length, precision, scale } = attribute
  if (type === 'DECIMAL' && precision !== undefined) {
    return scale !== undefined ? `${type}(${precision},${scale})` : `${type}(${precision})`
  }
  if (length !== undefined && (type === 'VARCHAR' || type === 'TEXT')) {
    return `${type}(${length})`
  }
  return type
}
