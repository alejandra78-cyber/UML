import { useCallback, useMemo, useState } from 'react'
import { Handle, Position as FlowPosition, type NodeProps } from 'reactflow'
import { useDiagramStore } from '../store/useDiagramStore'
import type { Attribute, AttributeType, ClassEntity } from '../types/diagram'
import './ErTableNode.css'

// El backend deserializa el id como java.util.UUID (ver Attribute en metamodel/model):
// un id con prefijo tipo "attr-abc123" no es un UUID válido (misma nota que en
// UmlClassNode.tsx).
function createId(): string {
  return crypto.randomUUID()
}

interface ErTableNodeData {
  classEntity: ClassEntity
}

/** "VARCHAR(255)" / "DECIMAL(10,2)" / "INTEGER" -- notación de tipo SQL para la vista ER. */
function formatSqlType(attribute: Attribute): string {
  const { type, length, precision, scale } = attribute
  if (type === 'DECIMAL' && precision !== undefined) {
    return scale !== undefined ? `${type}(${precision},${scale})` : `${type}(${precision})`
  }
  if (length !== undefined && (type === 'VARCHAR' || type === 'TEXT')) {
    return `${type}(${length})`
  }
  return type
}

/**
 * Heurística visual best-effort para el badge FK (no existe un campo canónico
 * "isForeignKey" -- ver nota de RF-01.6): una columna se marca como FK si su nombre
 * hace match con el de alguna clase relacionada donde esta tabla es el lado
 * "target" de la relación (el lado que típicamente contendría la clave foránea en
 * un mapeo relacional 1..N). Es solo presentación: no muta el modelo canónico.
 */
function useForeignKeyColumnIds(classEntity: ClassEntity): Set<string> {
  const relationships = useDiagramStore((state) => state.model.relationships)
  const classes = useDiagramStore((state) => state.model.classes)

  return useMemo(() => {
    const fkIds = new Set<string>()
    const relatedSourceNames = relationships
      .filter((r) => r.targetClassId === classEntity.id && r.sourceClassId !== classEntity.id)
      .map((r) => classes.find((c) => c.id === r.sourceClassId)?.name)
      .filter((name): name is string => Boolean(name))
      .map((name) => name.toLowerCase())

    if (relatedSourceNames.length === 0) return fkIds

    for (const attribute of classEntity.attributes) {
      const attrName = attribute.name.toLowerCase()
      const matches = relatedSourceNames.some(
        (name) => attrName === `${name}id` || attrName === `${name}_id` || attrName === `id${name}`,
      )
      if (matches) fkIds.add(attribute.id)
    }
    return fkIds
  }, [relationships, classes, classEntity.id, classEntity.attributes])
}

export function ErTableNode({ data }: NodeProps<ErTableNodeData>) {
  const { classEntity } = data
  const updateClass = useDiagramStore((state) => state.updateClass)
  const togglePrimaryKeyAction = useDiagramStore((state) => state.togglePrimaryKey)
  const foreignKeyColumnIds = useForeignKeyColumnIds(classEntity)

  const [editingName, setEditingName] = useState(false)
  const [nameDraft, setNameDraft] = useState(classEntity.name)

  const [editingAttrId, setEditingAttrId] = useState<string | null>(null)
  const [attrDraft, setAttrDraft] = useState('')

  function startEditName() {
    setNameDraft(classEntity.name)
    setEditingName(true)
  }

  const commitName = useCallback(() => {
    setEditingName(false)
    const trimmed = nameDraft.trim()
    if (trimmed && trimmed !== classEntity.name) {
      updateClass(classEntity.id, { name: trimmed })
    }
  }, [classEntity.id, classEntity.name, nameDraft, updateClass])

  function startEditAttribute(attribute: Attribute) {
    setEditingAttrId(attribute.id)
    setAttrDraft(`${attribute.name}: ${attribute.type}`)
  }

  function commitAttribute(attribute: Attribute) {
    const [namePart, typePart] = attrDraft.split(':').map((part) => part.trim())
    if (namePart) {
      const nextAttributes = classEntity.attributes.map((a) =>
        a.id === attribute.id
          ? { ...a, name: namePart, type: (typePart?.toUpperCase() as AttributeType) || a.type }
          : a,
      )
      updateClass(classEntity.id, { attributes: nextAttributes })
    }
    setEditingAttrId(null)
  }

  function addAttribute() {
    // Mismos defaults del esquema canónico que UmlClassNode.addAttribute (sección 7
    // del PLAN_ARQUITECTONICO.md) -- el backend deserializa Attribute como record de
    // Java y rechaza en silencio payloads incompletos.
    const newAttribute: Attribute = {
      id: createId(),
      name: 'nueva_columna',
      type: 'VARCHAR',
      visibility: 'PRIVATE',
      length: 255,
      precision: 10,
      scale: 2,
      isPrimaryKey: false,
      isNullable: true,
      isUnique: false,
      defaultValue: null,
    }
    updateClass(classEntity.id, { attributes: [...classEntity.attributes, newAttribute] })
  }

  function deleteAttribute(attributeId: string) {
    updateClass(classEntity.id, { attributes: classEntity.attributes.filter((a) => a.id !== attributeId) })
  }


  return (
    <div className="er-table-node">
      <Handle type="target" position={FlowPosition.Left} />
      <Handle type="source" position={FlowPosition.Right} />

      <div className="er-table-node__header" onDoubleClick={startEditName}>
        {editingName ? (
          <input
            autoFocus
            className="nodrag"
            value={nameDraft}
            onChange={(e) => setNameDraft(e.target.value)}
            onBlur={commitName}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commitName()
              if (e.key === 'Escape') setEditingName(false)
            }}
          />
        ) : (
          <span>{classEntity.name}</span>
        )}
      </div>

      <table className="er-table-node__table">
        <tbody>
          {classEntity.attributes.map((attribute, index) => {
            const isFk = foreignKeyColumnIds.has(attribute.id)
            // Punto 1: línea separadora debajo de la(s) columna(s) PK -- patrón
            // estándar de diagramas ER. Asume la convención habitual de que las PK
            // van al principio de la tabla: se marca el último renglón de la
            // racha inicial de filas con isPrimaryKey=true (no reordena nada, es
            // solo el estilo de esa fila puntual).
            const isLastLeadingPk = attribute.isPrimaryKey && classEntity.attributes[index + 1]?.isPrimaryKey !== true
            return (
              <tr
                key={attribute.id}
                className={`er-table-node__row${isLastLeadingPk ? ' er-table-node__row--pk-divider' : ''}`}
                onDoubleClick={() => startEditAttribute(attribute)}
              >
                {editingAttrId === attribute.id ? (
                  <td colSpan={3}>
                    <input
                      autoFocus
                      className="nodrag"
                      value={attrDraft}
                      onChange={(e) => setAttrDraft(e.target.value)}
                      onFocus={(e) => e.target.select()}
                      onBlur={() => commitAttribute(attribute)}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter') commitAttribute(attribute)
                        if (e.key === 'Escape') setEditingAttrId(null)
                      }}
                    />
                  </td>
                ) : (
                  <>
                    <td
                      className="er-table-node__badges nodrag"
                      title={attribute.isPrimaryKey ? 'Clic para quitar la clave primaria' : 'Clic para marcar como clave primaria'}
                      onClick={(e) => {
                        e.stopPropagation()
                        togglePrimaryKeyAction(classEntity.id, attribute.id)
                      }}
                    >
                      {attribute.isPrimaryKey ? (
                        <span className="er-badge er-badge--pk">PK</span>
                      ) : (
                        <span className="er-badge er-badge--pk-placeholder">PK</span>
                      )}
                    </td>
                    <td className="er-table-node__col-name">
                      {attribute.name}
                      {/* Punto 1: "(FK)" junto al nombre, no como badge aparte. */}
                      {!attribute.isPrimaryKey && isFk ? <span className="er-fk-label"> (FK)</span> : null}
                    </td>
                    <td className="er-table-node__col-type">
                      {formatSqlType(attribute)}
                      {attribute.isNullable === false ? <span className="er-not-null"> NOT NULL</span> : null}
                    </td>
                    <td>
                      <button
                        type="button"
                        className="er-table-node__delete nodrag"
                        title="Eliminar columna"
                        onClick={(e) => {
                          e.stopPropagation()
                          deleteAttribute(attribute.id)
                        }}
                      >
                        ×
                      </button>
                    </td>
                  </>
                )}
              </tr>
            )
          })}
        </tbody>
      </table>
      <button type="button" className="er-table-node__add nodrag" onClick={addAttribute}>
        + columna
      </button>
    </div>
  )
}
