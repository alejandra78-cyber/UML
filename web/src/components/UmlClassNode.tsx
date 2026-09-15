import { useCallback, useEffect, useRef, useState } from 'react'
import { Handle, Position as FlowPosition, type NodeProps } from 'reactflow'
import { useDiagramStore } from '../store/useDiagramStore'
import { usePresenceStore } from '../store/usePresenceStore'
import { ClassContextMenu } from './ClassContextMenu'
import type { Attribute, AttributeType, ClassEntity, Method, Visibility } from '../types/diagram'
import { ATTRIBUTE_TYPES, formatAttributeType, resolveAttributeType } from '../utils/attributeType'
import './UmlClassNode.css'

const VISIBILITY_SYMBOL: Record<Visibility, string> = {
  PUBLIC: '+',
  PRIVATE: '-',
  PROTECTED: '#',
  PACKAGE: '~',
}

const VISIBILITY_NAME: Record<Visibility, string> = {
  PUBLIC: 'Pública (+)',
  PRIVATE: 'Privada (-)',
  PROTECTED: 'Protegida (#)',
  PACKAGE: 'Paquete (~)',
}

// Ciclo de visibilidad UML 2.5.1 (los 4 símbolos oficiales): un clic en el símbolo
// de visibilidad de un atributo/método avanza al siguiente, para poder elegirla al
// crear o editar sin necesitar un <select> aparte.
const VISIBILITY_ORDER: Visibility[] = ['PUBLIC', 'PRIVATE', 'PROTECTED', 'PACKAGE']

function nextVisibility(current: Visibility): Visibility {
  const index = VISIBILITY_ORDER.indexOf(current)
  return VISIBILITY_ORDER[(index + 1) % VISIBILITY_ORDER.length]
}

// Todo atributo/método DEBE mostrar su prefijo de visibilidad (+/-/#/~), sin
// excepción -- pero `visibility` no siempre llega con uno de los 4 valores
// válidos del enum (p.ej. datos generados por el importador de IA/Vision, que
// arma el JSON del modelo desde una imagen y puede omitir el campo o mandar un
// valor no reconocido): VISIBILITY_SYMBOL[valor no reconocido] da `undefined` y
// el botón queda mudo. Se resuelve al símbolo de PUBLIC como default visual,
// igual que resolveAttributeType ya hace ese mismo fallback para el tipo -- no
// toca el campo `visibility` guardado, solo qué símbolo se muestra.
function resolveVisibilitySymbol(visibility: Visibility): string {
  return VISIBILITY_SYMBOL[visibility] ?? VISIBILITY_SYMBOL.PUBLIC
}

function resolveVisibilityName(visibility: Visibility): string {
  return VISIBILITY_NAME[visibility] ?? VISIBILITY_NAME.PUBLIC
}

/**
 * Badges de modificadores (PK, Unique, NOT NULL, Default) junto al atributo, en vez
 * de texto plano dentro del nombre. No hay campo canónico "isForeignKey" en el
 * esquema (ver types/diagram.ts), así que no se muestra badge FK acá -- la vista ER
 * ya tiene su propia heurística visual best-effort para eso (ErTableNode).
 */
// El toggle de PK necesita `onTogglePk` (updateClass vive en el componente, no
// acá): ver la nota en UmlClassNode sobre por qué el campo isPrimaryKey nunca
// aparecía en uso real -- existía en el esquema y se renderizaba bien, pero no
// había ninguna forma de MARCARLO desde la interfaz.
function renderAttributeBadges(attribute: Attribute, onTogglePk: () => void) {
  const { recognized } = resolveAttributeType(attribute.type)
  return (
    <>
      <button
        type="button"
        className={`uml-badge nodrag ${attribute.isPrimaryKey ? 'uml-badge--pk' : 'uml-badge--pk-placeholder'}`}
        title={attribute.isPrimaryKey ? 'Clic para quitar la clave primaria' : 'Clic para marcar como clave primaria'}
        onClick={(e) => {
          e.stopPropagation()
          onTogglePk()
        }}
      >
        PK
      </button>
      {attribute.isUnique ? <span className="uml-badge uml-badge--unique">U</span> : null}
      {attribute.isNullable === false ? <span className="uml-badge uml-badge--notnull">NN</span> : null}
      {attribute.defaultValue ? (
        <span className="uml-badge uml-badge--default" title={`Valor por defecto: ${attribute.defaultValue}`}>
          ={attribute.defaultValue}
        </span>
      ) : null}
      {!recognized ? (
        <span className="uml-badge uml-badge--unknown-type" title="Tipo no reconocido en el enum canónico">
          ⚠ tipo no reconocido
        </span>
      ) : null}
    </>
  )
}

interface UmlClassNodeData {
  classEntity: ClassEntity
}

export function UmlClassNode({ data, selected }: NodeProps<UmlClassNodeData>) {
  const { classEntity } = data
  const updateClass = useDiagramStore((state) => state.updateClass)
  const addAttributeAction = useDiagramStore((state) => state.addAttribute)
  const addMethodAction = useDiagramStore((state) => state.addMethod)
  const moveAttributeAction = useDiagramStore((state) => state.moveAttribute)
  const moveMethodAction = useDiagramStore((state) => state.moveMethod)
  const togglePrimaryKeyAction = useDiagramStore((state) => state.togglePrimaryKey)
  // Soft-lock ajeno activo sobre esta clase (sección 8.2): pinta borde + candado
  // y deshabilita la edición inline mientras el otro usuario la tiene bloqueada.
  const lock = usePresenceStore((state) => state.activeLocks[classEntity.id])

  const [editingName, setEditingName] = useState(false)
  const [nameDraft, setNameDraft] = useState(classEntity.name)

  const [editingAttrId, setEditingAttrId] = useState<string | null>(null)
  const [attrNameDraft, setAttrNameDraft] = useState('')
  const [attrTypeDraft, setAttrTypeDraft] = useState('')
  const attrNameInputRef = useRef<HTMLInputElement>(null)

  const [editingMethodId, setEditingMethodId] = useState<string | null>(null)
  const [methodDraft, setMethodDraft] = useState('')
  const methodInputRef = useRef<HTMLInputElement>(null)

  // Menú contextual estilo Sparx EA (punto 2): clic derecho sobre la clase.
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number } | null>(null)

  function openContextMenu(e: React.MouseEvent) {
    e.preventDefault()
    e.stopPropagation()
    setContextMenu({ x: e.clientX, y: e.clientY })
  }

  // Foco + selección automáticos al entrar en modo edición (punto 2: "input con
  // foco automático, texto seleccionado"). NO se delega a `autoFocus`: cuando la
  // edición se dispara desde "Agregar > Atributo" del menú contextual, algo
  // MUEVE el foco al <body> después de que el efecto corre -- confirmado con
  // Playwright, tanto `autoFocus` como un `useEffect`/`useLayoutEffect` sin
  // demora perdían la carrera contra eso (sospecha: el propio manejo de foco de
  // reactflow al detectar el clic fuera del pane/nodo, en un listener nativo que
  // corre después). Empujar el `.focus()` a la SIGUIENTE tarea del event loop
  // (setTimeout 0) lo hace ganar siempre, ya sea cual sea la causa exacta.
  useEffect(() => {
    if (!editingAttrId) return
    const timer = window.setTimeout(() => {
      attrNameInputRef.current?.focus()
      attrNameInputRef.current?.select()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [editingAttrId])

  useEffect(() => {
    if (!editingMethodId) return
    const timer = window.setTimeout(() => {
      methodInputRef.current?.focus()
      methodInputRef.current?.select()
    }, 0)
    return () => window.clearTimeout(timer)
  }, [editingMethodId])

  function startEditName() {
    // Siempre parte del valor ACTUAL de classEntity.name, no del que haya quedado
    // en nameDraft de una edición anterior (o del valor de montaje inicial): sin
    // este reseteo, reabrir el editor podía mostrar un valor viejo y, si el
    // usuario confirmaba sin notarlo, "revertía" el nombre a ese valor viejo.
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
    setAttrNameDraft(attribute.name)
    setAttrTypeDraft(resolveAttributeType(attribute.type).value)
  }

  function commitAttribute(attribute: Attribute) {
    const namePart = attrNameDraft.trim()
    if (namePart) {
      const nextAttributes = classEntity.attributes.map((a) =>
        a.id === attribute.id ? { ...a, name: namePart, type: attrTypeDraft as AttributeType } : a,
      )
      updateClass(classEntity.id, { attributes: nextAttributes })
    }
    setEditingAttrId(null)
  }

  // Camino único para "agregar atributo": lo usan tanto la fila rápida "+ atributo"
  // (hover/selección) como "Agregar > Atributo" del menú contextual (punto 2) --
  // ambos llaman a esta misma función, que a su vez llama a la misma acción del
  // store (addAttribute, que ya arma los defaults del esquema canónico y
  // finalmente termina en el mismo ADD_ATTRIBUTE de siempre). La diferencia con el
  // comportamiento anterior es que ahora entra directo en modo edición inline
  // sobre la fila nueva, con foco automático y el texto preseleccionado (ver el
  // input del nombre, más abajo).
  function addAttribute() {
    const newId = addAttributeAction(classEntity.id)
    setEditingAttrId(newId)
    setAttrNameDraft('nuevoAtributo')
    setAttrTypeDraft('VARCHAR')
  }

  function startEditMethod(method: Method) {
    setEditingMethodId(method.id)
    setMethodDraft(`${method.name}(): ${method.returnType}`)
  }

  function commitMethod(method: Method) {
    const match = methodDraft.match(/^(.*?)\s*\((.*)\)\s*:\s*(.*)$/)
    if (match) {
      const [, namePart, , returnTypePart] = match
      const nextMethods = classEntity.methods.map((m) =>
        m.id === method.id
          ? { ...m, name: namePart.trim() || m.name, returnType: returnTypePart.trim() || m.returnType }
          : m,
      )
      updateClass(classEntity.id, { methods: nextMethods })
    }
    setEditingMethodId(null)
  }

  // Mismo camino único que addAttribute, para métodos.
  function addMethod() {
    const newId = addMethodAction(classEntity.id)
    setEditingMethodId(newId)
    setMethodDraft('nuevoMetodo(): void')
  }

  function cycleAttributeVisibility(attribute: Attribute) {
    const nextAttributes = classEntity.attributes.map((a) =>
      a.id === attribute.id ? { ...a, visibility: nextVisibility(a.visibility) } : a,
    )
    updateClass(classEntity.id, { attributes: nextAttributes })
  }


  function cycleMethodVisibility(method: Method) {
    const nextMethods = classEntity.methods.map((m) =>
      m.id === method.id ? { ...m, visibility: nextVisibility(m.visibility) } : m,
    )
    updateClass(classEntity.id, { methods: nextMethods })
  }

  function deleteAttribute(attributeId: string) {
    updateClass(classEntity.id, { attributes: classEntity.attributes.filter((a) => a.id !== attributeId) })
  }

  function deleteMethod(methodId: string) {
    updateClass(classEntity.id, { methods: classEntity.methods.filter((m) => m.id !== methodId) })
  }

  return (
    <div
      className={`uml-class-node${lock ? ' uml-class-node--locked' : ''}${selected ? ' uml-class-node--selected' : ''}`}
      style={lock ? { borderColor: lock.color, boxShadow: `0 0 0 2px ${lock.color}` } : undefined}
      onContextMenu={openContextMenu}
    >
      {contextMenu && (
        <ClassContextMenu
          x={contextMenu.x}
          y={contextMenu.y}
          onAddAttribute={addAttribute}
          onAddMethod={addMethod}
          onClose={() => setContextMenu(null)}
        />
      )}
      <Handle type="target" position={FlowPosition.Left} />
      <Handle type="source" position={FlowPosition.Right} />

      {lock && (
        <div
          className="uml-class-node__lock-badge"
          style={{ background: lock.color }}
          title={`Editado por ${lock.userName}`}
        >
          🔒
        </div>
      )}

      <div className={lock ? 'uml-class-node__body uml-class-node__body--disabled' : 'uml-class-node__body'}>
      <div className="uml-class-node__header" onDoubleClick={startEditName}>
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

      <div className="uml-class-node__section">
        {classEntity.attributes.map((attribute, index) => (
          <div
            key={attribute.id}
            className="uml-class-node__row"
            onDoubleClick={() => startEditAttribute(attribute)}
          >
            {editingAttrId === attribute.id ? (
              <span
                className="uml-class-node__edit-row"
                onBlur={(e) => {
                  // Solo confirma cuando el foco sale del GRUPO completo (input +
                  // select), no al moverse entre uno y otro con Tab/clic -- si no,
                  // pasar del nombre al tipo cerraba el editor a mitad de camino.
                  if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
                    commitAttribute(attribute)
                  }
                }}
              >
                <input
                  ref={attrNameInputRef}
                  className="nodrag uml-class-node__name-input"
                  value={attrNameDraft}
                  onChange={(e) => setAttrNameDraft(e.target.value)}
                  onFocus={(e) => e.target.select()}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commitAttribute(attribute)
                    if (e.key === 'Escape') setEditingAttrId(null)
                  }}
                />
                <span className="uml-class-node__colon">:</span>
                <select
                  className="nodrag uml-class-node__type-select"
                  value={attrTypeDraft}
                  onChange={(e) => setAttrTypeDraft(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') commitAttribute(attribute)
                    if (e.key === 'Escape') setEditingAttrId(null)
                  }}
                >
                  {!ATTRIBUTE_TYPES.includes(attrTypeDraft as AttributeType) && (
                    <option value={attrTypeDraft}>{attrTypeDraft} (no reconocido)</option>
                  )}
                  {ATTRIBUTE_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
              </span>
            ) : (
              <>
                <span className="uml-class-node__attr-line">
                  <button
                    type="button"
                    className="uml-class-node__visibility nodrag"
                    title={`Visibilidad: ${resolveVisibilityName(attribute.visibility)} (clic para cambiar)`}
                    onClick={(e) => {
                      e.stopPropagation()
                      cycleAttributeVisibility(attribute)
                    }}
                  >
                    {resolveVisibilitySymbol(attribute.visibility)}
                  </button>{' '}
                  {attribute.name} : {formatAttributeType(attribute)}
                  {renderAttributeBadges(attribute, () => togglePrimaryKeyAction(classEntity.id, attribute.id))}
                </span>
                <span className="uml-class-node__row-actions nodrag">
                  <button
                    type="button"
                    className="uml-class-node__row-action"
                    title="Mover arriba"
                    disabled={index === 0}
                    onClick={(e) => {
                      e.stopPropagation()
                      moveAttributeAction(classEntity.id, attribute.id, 'up')
                    }}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className="uml-class-node__row-action"
                    title="Mover abajo"
                    disabled={index === classEntity.attributes.length - 1}
                    onClick={(e) => {
                      e.stopPropagation()
                      moveAttributeAction(classEntity.id, attribute.id, 'down')
                    }}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    className="uml-class-node__row-action uml-class-node__row-action--delete"
                    title="Eliminar atributo"
                    onClick={(e) => {
                      e.stopPropagation()
                      deleteAttribute(attribute.id)
                    }}
                  >
                    ×
                  </button>
                </span>
              </>
            )}
          </div>
        ))}
        <button type="button" className="uml-class-node__add nodrag" onClick={addAttribute}>
          + atributo
        </button>
      </div>

      <div className="uml-class-node__section">
        {classEntity.methods.map((method, index) => (
          <div
            key={method.id}
            className="uml-class-node__row"
            onDoubleClick={() => startEditMethod(method)}
          >
            {editingMethodId === method.id ? (
              <input
                ref={methodInputRef}
                className="nodrag"
                value={methodDraft}
                onChange={(e) => setMethodDraft(e.target.value)}
                onFocus={(e) => e.target.select()}
                onBlur={() => commitMethod(method)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') commitMethod(method)
                  if (e.key === 'Escape') setEditingMethodId(null)
                }}
              />
            ) : (
              <>
                <span>
                  <button
                    type="button"
                    className="uml-class-node__visibility nodrag"
                    title={`Visibilidad: ${resolveVisibilityName(method.visibility)} (clic para cambiar)`}
                    onClick={(e) => {
                      e.stopPropagation()
                      cycleMethodVisibility(method)
                    }}
                  >
                    {resolveVisibilitySymbol(method.visibility)}
                  </button>{' '}
                  {method.name}(): {method.returnType}
                </span>
                <span className="uml-class-node__row-actions nodrag">
                  <button
                    type="button"
                    className="uml-class-node__row-action"
                    title="Mover arriba"
                    disabled={index === 0}
                    onClick={(e) => {
                      e.stopPropagation()
                      moveMethodAction(classEntity.id, method.id, 'up')
                    }}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className="uml-class-node__row-action"
                    title="Mover abajo"
                    disabled={index === classEntity.methods.length - 1}
                    onClick={(e) => {
                      e.stopPropagation()
                      moveMethodAction(classEntity.id, method.id, 'down')
                    }}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    className="uml-class-node__row-action uml-class-node__row-action--delete"
                    title="Eliminar método"
                    onClick={(e) => {
                      e.stopPropagation()
                      deleteMethod(method.id)
                    }}
                  >
                    ×
                  </button>
                </span>
              </>
            )}
          </div>
        ))}
        <button type="button" className="uml-class-node__add nodrag" onClick={addMethod}>
          + método
        </button>
      </div>
      </div>
    </div>
  )
}
