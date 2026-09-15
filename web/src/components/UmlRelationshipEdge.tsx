import { useState } from 'react'
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, useReactFlow, type EdgeProps } from 'reactflow'
import { useDiagramStore } from '../store/useDiagramStore'
import type { Multiplicity, Relationship, RelationshipType, Waypoint } from '../types/diagram'
import './UmlRelationshipEdge.css'

const VALID_MULTIPLICITIES: Multiplicity[] = ['0..1', '1..1', '0..*', '1..*']

function normalizeMultiplicity(raw: string): Multiplicity | null {
  const trimmed = raw.trim()
  if ((VALID_MULTIPLICITIES as string[]).includes(trimmed)) return trimmed as Multiplicity
  // Acepta variantes cortas comunes ("1", "*", "0-1", etc.) y las mapea al literal válido.
  if (trimmed === '1') return '1..1'
  if (trimmed === '*' || trimmed === '0..n' || trimmed === 'n') return '0..*'
  if (trimmed === '1..n') return '1..*'
  return null
}

// Los 6 tipos de relación pedidos para el selector visual (ASSOCIATION,
// AGGREGATION, COMPOSITION, INHERITANCE/GENERALIZATION, DEPENDENCY, MANY_TO_MANY --
// ver PLAN_ARQUITECTONICO.md, JSON Schema de Relationship). El modelo canónico
// conserva además GENERALIZATION/REALIZATION como valores formales distintos (UML
// 2.5.1 estricto, tarea previa) y MANY_TO_MANY para la generación de tabla
// intermedia JPA -- esta lista NO les cambia el valor subyacente, solo decide
// cuáles 6 opciones ofrece el <select> (capa de presentación, sin tocar el
// modelo): "Herencia" sigue guardándose como GENERALIZATION, y "Realización"
// (interfaz), aunque sigue soportada si ya existe en datos guardados, no se
// ofrece como opción nueva para no superar las 6 pedidas.
export const RELATIONSHIP_TYPE_OPTIONS: { value: RelationshipType; label: string; icon: string }[] = [
  { value: 'ASSOCIATION', label: 'Asociación', icon: '─' },
  { value: 'AGGREGATION', label: 'Agregación', icon: '◇' },
  { value: 'COMPOSITION', label: 'Composición', icon: '◆' },
  { value: 'GENERALIZATION', label: 'Herencia', icon: '▷' },
  { value: 'DEPENDENCY', label: 'Dependencia', icon: '⇢' },
  { value: 'MANY_TO_MANY', label: 'Muchos a muchos', icon: '⋈' },
]

// Tipos que en UML no llevan multiplicidad editable: Generalización/Realización son
// relaciones entre clasificadores (no estructurales); Muchos-a-muchos SÍ lleva
// multiplicidad, pero fija en "0..*" en ambos extremos (ver showFixedManyToMany).
const TYPES_WITHOUT_MULTIPLICITY = new Set<RelationshipType>(['GENERALIZATION', 'REALIZATION'])

// Tipos cuya línea es punteada según la notación oficial (Dependencia y
// Realización); el resto es línea sólida.
const DASHED_TYPES = new Set<RelationshipType>(['DEPENDENCY', 'REALIZATION'])

// Color de acento celeste, el mismo que los handles de conexión (ver
// .react-flow__handle en App.css): antes el trazo y los marcadores usaban
// #1a1a1a (casi negro), invisibles sobre el fondo oscuro del lienzo (#16171d).
// Deliberadamente NO es el verde de ErRelationshipEdge -- así se distingue a
// simple vista qué vista (UML/ER) está activa. `style` es un prop inline en
// BaseEdge, así que gana por especificidad sobre `.react-flow__edge.selected
// .react-flow__edge-path { stroke: #555 }` del stylesheet default de reactflow
// (ese #555 nunca llegaba a aplicarse); por eso el color de "seleccionado" se
// resuelve acá mismo, a mano, en vez de depender de esa regla.
const UML_EDGE_COLOR = '#38bdf8'
const UML_EDGE_COLOR_SELECTED = '#a5e8ff'

interface UmlRelationshipEdgeData {
  relationship: Relationship
}

type EditableEnd = 'source' | 'target'

/**
 * Decide en qué extremo del trazo va el rombo (Agregación/Composición): en UML no
 * importa "quién dibujó primero", sino cuál extremo es la clase "todo"/contenedora.
 * Este modelo no tiene un campo dedicado para eso, así que se reutiliza
 * `owningSide` (SOURCE/TARGET, ya existente para la generación ORM) como la mejor
 * aproximación disponible: el lado propietario de la referencia/colección es,
 * en la práctica, casi siempre la clase "todo".
 */
function wholeEnd(relationship: Relationship): EditableEnd {
  return relationship.owningSide === 'TARGET' ? 'target' : 'source'
}

export function UmlRelationshipEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
}: EdgeProps<UmlRelationshipEdgeData>) {
  const relationship = data!.relationship
  const updateRelationship = useDiagramStore((state) => state.updateRelationship)
  const updateWaypoints = useDiagramStore((state) => state.updateWaypoints)
  const { screenToFlowPosition } = useReactFlow()

  const [editingEnd, setEditingEnd] = useState<EditableEnd | null>(null)
  const [draft, setDraft] = useState('')

  // Waypoint arrastrable (sección 8.1: "waypoints interactivos"). Solo se soporta uno
  // por ahora -- suficiente para reacomodar dónde dobla la línea y dónde queda el label
  // de cardinalidad. Mientras el usuario lo arrastra, dragPosition sobreescribe
  // localmente la posición para que se sienta fluido sin esperar el viaje por STOMP.
  const savedWaypoint: Waypoint | undefined = relationship.waypoints?.[0]
  const defaultMidpoint: Waypoint = { x: (sourceX + targetX) / 2, y: (sourceY + targetY) / 2 }
  const [dragPosition, setDragPosition] = useState<Waypoint | null>(null)
  const waypoint = dragPosition ?? savedWaypoint ?? defaultMidpoint

  const path = savedWaypoint || dragPosition
    ? `M ${sourceX},${sourceY} L ${waypoint.x},${waypoint.y} L ${targetX},${targetY}`
    : getSmoothStepPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition })[0]

  // Etiquetas ubicadas al 20% y al 80% del segmento recto entre los dos extremos
  // (una aproximación simple y suficiente para el trazo ortogonal de smoothstep).
  const sourceLabelPos = { x: sourceX + (targetX - sourceX) * 0.2, y: sourceY + (targetY - sourceY) * 0.2 }
  const targetLabelPos = { x: sourceX + (targetX - sourceX) * 0.8, y: sourceY + (targetY - sourceY) * 0.8 }
  // Anclado al mismo punto que el manejador de waypoint (el bend real de la línea,
  // o el punto medio por defecto si no se arrastró ninguno) -- no a un punto medio
  // independiente, para que nunca "flote" desviado de la línea visible.
  const typeSelectorPos = { x: waypoint.x, y: waypoint.y - 22 }

  function startEdit(end: EditableEnd) {
    setEditingEnd(end)
    setDraft((end === 'source' ? relationship.sourceMultiplicity : relationship.targetMultiplicity) ?? '')
  }

  function commit(end: EditableEnd) {
    const normalized = normalizeMultiplicity(draft)
    if (normalized) {
      updateRelationship(relationship.id, end === 'source' ? { sourceMultiplicity: normalized } : { targetMultiplicity: normalized })
    }
    setEditingEnd(null)
  }

  function renderLabel(end: EditableEnd, pos: { x: number; y: number }) {
    const value = end === 'source' ? relationship.sourceMultiplicity : relationship.targetMultiplicity
    const isEditing = editingEnd === end
    return (
      <div
        className="uml-edge-label nodrag nopan"
        style={{ transform: `translate(-50%, -50%) translate(${pos.x}px, ${pos.y}px)` }}
        onDoubleClick={() => startEdit(end)}
      >
        {isEditing ? (
          <input
            autoFocus
            className="nodrag"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onBlur={() => commit(end)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commit(end)
              if (e.key === 'Escape') setEditingEnd(null)
            }}
          />
        ) : (
          <span>{value ?? '?'}</span>
        )}
      </div>
    )
  }

  /** Muchos-a-muchos se renderiza en vista UML como una asociación con multiplicidad
   * fija "0..*" en ambos extremos (no editable): es notación derivada del tipo, no
   * un valor libre a elegir, así que no abre el editor de doble clic. */
  function renderFixedManyToManyLabel(pos: { x: number; y: number }) {
    return (
      <div
        className="uml-edge-label uml-edge-label--fixed nodrag nopan"
        style={{ transform: `translate(-50%, -50%) translate(${pos.x}px, ${pos.y}px)` }}
        title="Muchos a muchos: multiplicidad fija 0..* en ambos extremos"
      >
        <span>0..*</span>
      </div>
    )
  }

  function onWaypointPointerDown(e: React.PointerEvent<HTMLDivElement>) {
    e.stopPropagation()
    e.currentTarget.setPointerCapture(e.pointerId)
    setDragPosition(waypoint)
  }

  function onWaypointPointerMove(e: React.PointerEvent<HTMLDivElement>) {
    if (dragPosition === null) return
    e.stopPropagation()
    setDragPosition(screenToFlowPosition({ x: e.clientX, y: e.clientY }))
  }

  function onWaypointPointerUp(e: React.PointerEvent<HTMLDivElement>) {
    if (dragPosition === null) return
    e.stopPropagation()
    e.currentTarget.releasePointerCapture(e.pointerId)
    updateWaypoints(relationship.id, [dragPosition])
    setDragPosition(null)
  }

  // --- Notación OMG UML 2.5.1 (sección "Class Diagrams") ---
  const type = relationship.type
  const dashed = DASHED_TYPES.has(type)
  const showMultiplicity = !TYPES_WITHOUT_MULTIPLICITY.has(type)

  // IDs de marker únicos POR EDGE (no por tipo): un <marker> con id duplicado entre
  // múltiples instancias de este componente es inválido en SVG (aunque los
  // navegadores lo toleren en la práctica al ser contenido idéntico); prefijar con
  // `id` (el id del edge, único) evita el problema sin necesitar un <defs> global
  // compartido fuera de este componente.
  const arrowOpenId = `uml-arrow-open-${id}`
  const diamondEmptyId = `uml-diamond-empty-${id}`
  const diamondFilledId = `uml-diamond-filled-${id}`
  const triangleEmptyId = `uml-triangle-empty-${id}`

  const whole = wholeEnd(relationship)

  // markerEnd/markerStart según el tipo: el rombo de Agregación/Composición va en
  // el extremo "todo" (wholeEnd); la flecha de Generalización/Realización va
  // siempre en el extremo "padre"/"interfaz" (target, por convención: se dibuja
  // desde la subclase/implementación HACIA el padre/interfaz); Dependencia lleva
  // flecha abierta en target. Asociación NO lleva flecha en ningún extremo: el
  // esquema canónico solo tiene `isNavigable` como campo GLOBAL (no navegabilidad
  // separada por extremo), así que no hay forma consistente de representar
  // asimetría -- se dibuja como línea sólida sin decoración en ambos extremos,
  // igual de no dirigida/bidireccional que una asociación real. `isNavigable` no
  // se lee acá a propósito (antes SÍ condicionaba la flecha, y por default venía
  // en true -- por eso la flecha aparecía siempre en la práctica).
  const strokeColor = selected ? UML_EDGE_COLOR_SELECTED : UML_EDGE_COLOR

  let markerStart: string | undefined
  let markerEnd: string | undefined

  if (type === 'AGGREGATION' || type === 'COMPOSITION') {
    const diamondId = type === 'COMPOSITION' ? diamondFilledId : diamondEmptyId
    if (whole === 'source') markerStart = `url(#${diamondId}-start)`
    else markerEnd = `url(#${diamondId})`
  } else if (type === 'GENERALIZATION' || type === 'REALIZATION') {
    markerEnd = `url(#${triangleEmptyId})`
  } else if (type === 'DEPENDENCY') {
    markerEnd = `url(#${arrowOpenId})`
  }

  return (
    <>
      <svg style={{ position: 'absolute', width: 0, height: 0 }} aria-hidden>
        <defs>
          <marker id={arrowOpenId} markerWidth="12" markerHeight="12" refX="10" refY="6" orient="auto">
            <path d="M 1.5 1.5 L 10 6 L 1.5 10.5" fill="none" stroke={strokeColor} strokeWidth="1.4" />
          </marker>
          <marker id={diamondEmptyId} markerWidth="18" markerHeight="10" refX="16" refY="5" orient="auto">
            <path d="M 1 5 L 8.5 1.2 L 16 5 L 8.5 8.8 Z" fill="none" stroke={strokeColor} strokeWidth="1.3" />
          </marker>
          <marker
            id={`${diamondEmptyId}-start`}
            markerWidth="18"
            markerHeight="10"
            refX="16"
            refY="5"
            orient="auto-start-reverse"
          >
            <path d="M 1 5 L 8.5 1.2 L 16 5 L 8.5 8.8 Z" fill="none" stroke={strokeColor} strokeWidth="1.3" />
          </marker>
          <marker id={diamondFilledId} markerWidth="18" markerHeight="10" refX="16" refY="5" orient="auto">
            <path d="M 1 5 L 8.5 1.2 L 16 5 L 8.5 8.8 Z" fill={strokeColor} stroke={strokeColor} strokeWidth="1.3" />
          </marker>
          <marker
            id={`${diamondFilledId}-start`}
            markerWidth="18"
            markerHeight="10"
            refX="16"
            refY="5"
            orient="auto-start-reverse"
          >
            <path d="M 1 5 L 8.5 1.2 L 16 5 L 8.5 8.8 Z" fill={strokeColor} stroke={strokeColor} strokeWidth="1.3" />
          </marker>
          <marker id={triangleEmptyId} markerWidth="14" markerHeight="12" refX="12" refY="6" orient="auto">
            <path d="M 1 1 L 1 11 L 12.5 6 Z" fill="none" stroke={strokeColor} strokeWidth="1.3" />
          </marker>
        </defs>
      </svg>
      <BaseEdge
        id={id}
        path={path}
        markerStart={markerStart}
        markerEnd={markerEnd}
        style={{
          stroke: strokeColor,
          strokeWidth: selected ? 2 : 1.5,
          strokeDasharray: dashed ? '6,4' : undefined,
        }}
      />
      <EdgeLabelRenderer>
        {type === 'MANY_TO_MANY' ? (
          <>
            {renderFixedManyToManyLabel(sourceLabelPos)}
            {renderFixedManyToManyLabel(targetLabelPos)}
          </>
        ) : (
          <>
            {showMultiplicity && renderLabel('source', sourceLabelPos)}
            {showMultiplicity && renderLabel('target', targetLabelPos)}
          </>
        )}
        <select
          className="uml-edge-type-select nodrag nopan"
          title="Tipo de relación (UML 2.5.1)"
          style={{ transform: `translate(-50%, -50%) translate(${typeSelectorPos.x}px, ${typeSelectorPos.y}px)` }}
          value={type}
          onChange={(e) => updateRelationship(relationship.id, { type: e.target.value as RelationshipType })}
        >
          {RELATIONSHIP_TYPE_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <div
          className="uml-edge-waypoint nodrag nopan"
          title="Arrastrar para doblar la línea"
          style={{ transform: `translate(-50%, -50%) translate(${waypoint.x}px, ${waypoint.y}px)` }}
          onPointerDown={onWaypointPointerDown}
          onPointerMove={onWaypointPointerMove}
          onPointerUp={onWaypointPointerUp}
        />
      </EdgeLabelRenderer>
    </>
  )
}
