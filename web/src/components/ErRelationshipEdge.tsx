import { useState } from 'react'
import { BaseEdge, EdgeLabelRenderer, Position, getSmoothStepPath, useReactFlow, type EdgeProps } from 'reactflow'
import { useDiagramStore } from '../store/useDiagramStore'
import type { Multiplicity, Relationship, Waypoint } from '../types/diagram'
import './ErRelationshipEdge.css'

const VALID_MULTIPLICITIES: Multiplicity[] = ['0..1', '1..1', '0..*', '1..*']

function normalizeMultiplicity(raw: string): Multiplicity | null {
  const trimmed = raw.trim()
  if ((VALID_MULTIPLICITIES as string[]).includes(trimmed)) return trimmed as Multiplicity
  if (trimmed === '1') return '1..1'
  if (trimmed === '*' || trimmed === '0..n' || trimmed === 'n') return '0..*'
  if (trimmed === '1..n') return '1..*'
  return null
}

interface ErRelationshipEdgeData {
  relationship: Relationship
}

type EditableEnd = 'source' | 'target'

/**
 * Notación "pata de gallo" (Crow's Foot) real, dibujada en coordenadas LOCALES
 * asumiendo que el nodo/entidad está en el origen (0,0) y el conector se aleja de
 * él a lo largo del eje +x local. El <g> que envuelve esto se traslada y rota
 * para alinear ese eje +x con la dirección real del segmento en cada extremo.
 *
 * Las 4 combinaciones estándar (punto 1 de la tarea) -- la versión anterior de
 * este componente dibujaba SIEMPRE 2 barras para "exactamente uno" Y para
 * "cero o uno" (le faltaba distinguirlos: "0..1" debe ser 1 barra + círculo, no
 * 2 barras + círculo), y para "1..*"/"0..*" dibujaba solo el abanico sin la
 * barra de "al menos uno" que corresponde a "1..*". Reescrito con 4 casos
 * explícitos en vez de la combinación isMany/isZero que los confundía:
 *   "1..1" -> barra doble (exactamente uno)
 *   "0..1" -> círculo + barra simple (cero o uno)
 *   "1..*" -> barra simple + abanico (uno o muchos)
 *   "0..*" -> círculo + abanico (cero o muchos)
 */
function CrowFootMarker({
  x,
  y,
  angleDeg,
  multiplicity,
}: {
  x: number
  y: number
  angleDeg: number
  multiplicity: Multiplicity | undefined
}) {
  const mult = multiplicity ?? '1..1'

  return (
    <g transform={`translate(${x}, ${y}) rotate(${angleDeg})`} className="er-crowfoot-marker">
      {mult === '1..1' && (
        <>
          <line x1={7} y1={-6} x2={7} y2={6} />
          <line x1={13} y1={-6} x2={13} y2={6} />
        </>
      )}
      {mult === '0..1' && (
        <>
          <line x1={8} y1={-6} x2={8} y2={6} />
          <circle cx={17} cy={0} r={5} />
        </>
      )}
      {/* "Muchos": pie de gallo real -- 3 puntas SEPARADAS que TOCAN la entidad
          exactamente en el punto de conexión (x1=0, igual que el origen del <g>,
          sin ningún hueco) y CONVERGEN en un solo punto más lejos de la entidad
          (x grande), como un pie apoyado contra el borde de la entidad. Antes las
          puntas arrancaban en x=3, no en x=0: quedaba un hueco de unos pocos px
          entre la horquilla y el handle de conexión, relleno solo por la línea
          principal sin decoración -- suficiente para que, a simple vista (zoom
          incluido), se leyera como "una flecha/triángulo flotando cerca de la
          entidad" en vez de "un pie apoyado contra la entidad", con la línea
          principal de fondo reforzando la lectura de flecha simple. Reportado y
          confirmado con captura de zoom. La barra/círculo de "al menos" van MÁS
          ALLÁ del punto de convergencia (más lejos de la entidad todavía),
          continuando hacia el resto del conector. */}
      {mult === '1..*' && (
        <>
          <line x1={0} y1={-9} x2={13} y2={0} />
          <line x1={0} y1={0} x2={13} y2={0} />
          <line x1={0} y1={9} x2={13} y2={0} />
          <line x1={19} y1={-6} x2={19} y2={6} />
        </>
      )}
      {mult === '0..*' && (
        <>
          <line x1={0} y1={-9} x2={13} y2={0} />
          <line x1={0} y1={0} x2={13} y2={0} />
          <line x1={0} y1={9} x2={13} y2={0} />
          <circle cx={21} cy={0} r={5} />
        </>
      )}
    </g>
  )
}

/**
 * Ángulo de salida del marcador Crow's Foot según el LADO fijo del handle
 * (ErTableNode siempre ancla target=Left / source=Right, sin docking dinámico),
 * no una aproximación diagonal hacia el waypoint/midpoint: esa aproximación
 * daba un ángulo correcto solo cuando las dos clases estaban alineadas en Y
 * (entonces la diagonal coincidía por casualidad con 0°/180°), pero con
 * cualquier desnivel vertical entre clases el ángulo calculado se alejaba mucho
 * de la dirección real de salida del handle -- confirmado con Playwright:
 * clases desalineadas en Y daban rotate(171°) en el origen y rotate(-8.6°) en
 * el destino, en vez de los 0°/180° reales, lo que deformaba la horquilla
 * hasta verse como un chevron doble o casi desaparecer contra la línea.
 */
function positionToAngle(position: Position): number {
  switch (position) {
    case Position.Right:
      return 0
    case Position.Left:
      return 180
    case Position.Top:
      return -90
    case Position.Bottom:
      return 90
  }
}

export function ErRelationshipEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
}: EdgeProps<ErRelationshipEdgeData>) {
  const relationship = data!.relationship
  const updateRelationship = useDiagramStore((state) => state.updateRelationship)
  const updateWaypoints = useDiagramStore((state) => state.updateWaypoints)
  const { screenToFlowPosition } = useReactFlow()

  const [editingEnd, setEditingEnd] = useState<EditableEnd | null>(null)
  const [draft, setDraft] = useState('')

  // Mismo mecanismo de waypoint arrastrable que UmlRelationshipEdge (sección 8.1):
  // se reutiliza updateWaypoints del store para mantener consistencia de UX entre
  // ambas vistas (solo cambia la presentación).
  const savedWaypoint: Waypoint | undefined = relationship.waypoints?.[0]
  const defaultMidpoint: Waypoint = { x: (sourceX + targetX) / 2, y: (sourceY + targetY) / 2 }
  const [dragPosition, setDragPosition] = useState<Waypoint | null>(null)
  const waypoint = dragPosition ?? savedWaypoint ?? defaultMidpoint

  const path = savedWaypoint || dragPosition
    ? `M ${sourceX},${sourceY} L ${waypoint.x},${waypoint.y} L ${targetX},${targetY}`
    : getSmoothStepPath({ sourceX, sourceY, sourcePosition, targetX, targetY, targetPosition })[0]

  // Ángulo de salida real de cada extremo, según el lado fijo de su handle
  // (ver positionToAngle) -- independiente de dónde caiga el waypoint/midpoint.
  const sourceAngle = positionToAngle(sourcePosition)
  const targetAngle = positionToAngle(targetPosition)

  // Muchos-a-muchos: pata de gallo (crow's foot) en AMBOS extremos, fija en "0..*"
  // sin importar sourceMultiplicity/targetMultiplicity guardados -- es notación
  // derivada del tipo de relación, igual que en UmlRelationshipEdge.
  const isManyToMany = relationship.type === 'MANY_TO_MANY'
  const sourceMultiplicityForMarker = isManyToMany ? '0..*' : relationship.sourceMultiplicity
  const targetMultiplicityForMarker = isManyToMany ? '0..*' : relationship.targetMultiplicity

  // Etiquetas de multiplicidad (editables) posicionadas un poco más lejos del nodo
  // que el marcador Crow's Foot, para no superponerse visualmente con él.
  const sourceLabelPos = { x: sourceX + (targetX - sourceX) * 0.3, y: sourceY + (targetY - sourceY) * 0.3 }
  const targetLabelPos = { x: sourceX + (targetX - sourceX) * 0.7, y: sourceY + (targetY - sourceY) * 0.7 }

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

  /**
   * Punto 1: en vista ER la multiplicidad se comunica con el símbolo gráfico
   * Crow's Foot (CrowFootMarker), no con un badge de texto -- a diferencia de la
   * vista UML, donde el badge de texto sigue siendo correcto y no cambia. Por eso
   * acá, si NO se está editando, no se renderiza ningún texto visible: solo un
   * área de clic transparente sobre el símbolo gráfico, para poder seguir editando
   * la multiplicidad con doble clic exactamente igual que antes.
   */
  function renderLabel(end: EditableEnd, pos: { x: number; y: number }) {
    const isEditing = editingEnd === end
    return (
      <div
        className={`er-edge-label nodrag nopan${isEditing ? ' er-edge-label--editing' : ''}`}
        style={{ transform: `translate(-50%, -50%) translate(${pos.x}px, ${pos.y}px)` }}
        onDoubleClick={() => startEdit(end)}
        title="Doble clic para editar la multiplicidad"
      >
        {isEditing && (
          <input
            autoFocus
            className="nodrag"
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onFocus={(e) => e.target.select()}
            onBlur={() => commit(end)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') commit(end)
              if (e.key === 'Escape') setEditingEnd(null)
            }}
          />
        )}
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

  return (
    <>
      <BaseEdge id={id} path={path} style={{ stroke: '#2b5d34', strokeWidth: 1.5 }} />
      <CrowFootMarker x={sourceX} y={sourceY} angleDeg={sourceAngle} multiplicity={sourceMultiplicityForMarker} />
      <CrowFootMarker x={targetX} y={targetY} angleDeg={targetAngle} multiplicity={targetMultiplicityForMarker} />
      <EdgeLabelRenderer>
        {renderLabel('source', sourceLabelPos)}
        {renderLabel('target', targetLabelPos)}
        <div
          className="er-edge-waypoint nodrag nopan"
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
