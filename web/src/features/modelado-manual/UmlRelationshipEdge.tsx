import { useEffect, useState } from 'react'
import { BaseEdge, EdgeLabelRenderer, getSmoothStepPath, useReactFlow, type EdgeProps } from 'reactflow'
import { useDiagramStore } from '../../store/useDiagramStore'
import { getRectIntersection, getRectSide, rectCenter, useNodeRect } from '../../utils/edgeGeometry'
import {
  MULTIPLICITY_VALUES,
  type Multiplicity,
  type OwningSide,
  type Relationship,
  type RelationshipType,
  type Waypoint,
} from '../../types/diagram'
import './UmlRelationshipEdge.css'

// PKG-02 Modelado Manual — implementa UC06 (Crear/Editar Relación)

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
  source,
  target,
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
  const deleteRelationshipAction = useDiagramStore((state) => state.deleteRelationship)
  const updateWaypoints = useDiagramStore((state) => state.updateWaypoints)
  const { screenToFlowPosition } = useReactFlow()

  // Bug reportado con captura real: el punto de conexión quedaba FIJO en el
  // handle que originó el arrastre (siempre target=Left/source=Right, ver
  // UmlClassNode.tsx) sin importar hacia qué lado se moviera el waypoint o
  // dónde quedara el otro extremo -- la línea podía entrar cruzando por encima
  // del contenido de la clase en vez de por el borde más cercano. Patrón
  // estándar de reactflow ("Floating Edges"): se ignoran sourceX/Y/targetX/Y de
  // los props (esos SÍ dependen del handle fijo) y se recalcula el punto real
  // de intersección con el rectángulo de cada nodo en cada render, apuntando
  // hacia el waypoint (o hacia el centro del otro nodo si todavía no hay uno).
  const sourceRect = useNodeRect(source)
  const targetRect = useNodeRect(target)

  // Cierre de UC06 (corrección de UX): antes había VARIOS elementos flotando
  // siempre visibles de forma independiente (selector de tipo, botón de borrar,
  // fila de Nav./owningSide) que se pisaban entre sí y con los labels de
  // multiplicidad -- cada intento de "correrlos un poco" resolvía un choque y
  // creaba otro. Se reemplaza todo por UN solo panel consolidado que aparece
  // únicamente cuando la relación está seleccionada (React Flow ya selecciona al
  // hacer clic sobre la línea, sin código adicional acá), agrupando tipo,
  // multiplicidad+rol de ambos extremos, owningSide e isNavigable. En reposo
  // (no seleccionada) no flota NADA sobre el canvas salvo los labels de
  // multiplicidad de solo lectura -- nada con lo que pisarse.
  const [sourceRoleDraft, setSourceRoleDraft] = useState('')
  const [targetRoleDraft, setTargetRoleDraft] = useState('')

  useEffect(() => {
    if (selected) {
      setSourceRoleDraft(relationship.sourceRole ?? '')
      setTargetRoleDraft(relationship.targetRole ?? '')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- solo resetear el
    // draft en la transición no-seleccionada -> seleccionada, no en cada cambio
    // de relationship (evitaría pisar lo que el usuario esté tipeando si llega
    // un broadcast externo mientras el panel está abierto).
  }, [selected])

  // Waypoint arrastrable (sección 8.1: "waypoints interactivos"). Solo se soporta uno
  // por ahora -- suficiente para reacomodar dónde dobla la línea y dónde queda el label
  // de cardinalidad. Mientras el usuario lo arrastra, dragPosition sobreescribe
  // localmente la posición para que se sienta fluido sin esperar el viaje por STOMP.
  const savedWaypoint: Waypoint | undefined = relationship.waypoints?.[0]
  const [dragPosition, setDragPosition] = useState<Waypoint | null>(null)
  const activeWaypoint = dragPosition ?? savedWaypoint ?? null

  // Rectángulos en vivo de ambas clases (null en el primer render, antes de que
  // reactflow mida el nodo -- ver useNodeRect). Con ambos disponibles, cada
  // extremo apunta hacia el waypoint si existe uno, o si no hacia el centro del
  // OTRO nodo -- así, sin waypoint, el punto de conexión ya elige el lado más
  // cercano entre las dos clases en vez de un Left/Right fijo.
  const floatingSourcePoint =
    sourceRect && targetRect
      ? getRectIntersection(sourceRect, activeWaypoint ?? rectCenter(targetRect))
      : null
  const floatingTargetPoint =
    sourceRect && targetRect
      ? getRectIntersection(targetRect, activeWaypoint ?? rectCenter(sourceRect))
      : null

  // Fallback a los props de reactflow (posición del handle fijo) mientras el
  // nodo todavía no fue medido -- transitorio, dura un render.
  const resolvedSourceX = floatingSourcePoint?.x ?? sourceX
  const resolvedSourceY = floatingSourcePoint?.y ?? sourceY
  const resolvedTargetX = floatingTargetPoint?.x ?? targetX
  const resolvedTargetY = floatingTargetPoint?.y ?? targetY
  const resolvedSourcePosition =
    floatingSourcePoint && sourceRect ? getRectSide(sourceRect, floatingSourcePoint) : sourcePosition
  const resolvedTargetPosition =
    floatingTargetPoint && targetRect ? getRectSide(targetRect, floatingTargetPoint) : targetPosition

  const defaultMidpoint: Waypoint = {
    x: (resolvedSourceX + resolvedTargetX) / 2,
    y: (resolvedSourceY + resolvedTargetY) / 2,
  }
  const waypoint = activeWaypoint ?? defaultMidpoint

  const path = activeWaypoint
    ? `M ${resolvedSourceX},${resolvedSourceY} L ${waypoint.x},${waypoint.y} L ${resolvedTargetX},${resolvedTargetY}`
    : getSmoothStepPath({
        sourceX: resolvedSourceX,
        sourceY: resolvedSourceY,
        sourcePosition: resolvedSourcePosition,
        targetX: resolvedTargetX,
        targetY: resolvedTargetY,
        targetPosition: resolvedTargetPosition,
      })[0]

  // Etiquetas ubicadas al 20% y al 80% del segmento recto entre los dos extremos
  // (una aproximación simple y suficiente para el trazo ortogonal de smoothstep).
  const sourceLabelPos = {
    x: resolvedSourceX + (resolvedTargetX - resolvedSourceX) * 0.2,
    y: resolvedSourceY + (resolvedTargetY - resolvedSourceY) * 0.2,
  }
  const targetLabelPos = {
    x: resolvedSourceX + (resolvedTargetX - resolvedSourceX) * 0.8,
    y: resolvedSourceY + (resolvedTargetY - resolvedSourceY) * 0.8,
  }

  function commitSourceRole() {
    const trimmed = sourceRoleDraft.trim()
    if (trimmed !== (relationship.sourceRole ?? '')) {
      updateRelationship(relationship.id, { sourceRole: trimmed })
    }
  }

  function commitTargetRole() {
    const trimmed = targetRoleDraft.trim()
    if (trimmed !== (relationship.targetRole ?? '')) {
      updateRelationship(relationship.id, { targetRole: trimmed })
    }
  }

  /** Label de solo lectura (valor actual) -- la edición pasa por el panel consolidado
   * cuando la relación está seleccionada, no por interacción directa acá. Sin
   * pointer-events para que un clic sobre el label seleccione la relación (el clic
   * "atraviesa" hacia el path del edge) en vez de quedar atrapado en este div. */
  function renderLabel(end: EditableEnd, pos: { x: number; y: number }) {
    const value = end === 'source' ? relationship.sourceMultiplicity : relationship.targetMultiplicity
    const role = end === 'source' ? relationship.sourceRole : relationship.targetRole
    return (
      <div className="uml-edge-label" style={{ transform: `translate(-50%, -50%) translate(${pos.x}px, ${pos.y}px)` }}>
        <span>
          {value ?? '?'}
          {role ? ` (${role})` : ''}
        </span>
      </div>
    )
  }

  /** Muchos-a-muchos se renderiza en vista UML como una asociación con multiplicidad
   * fija "0..*" en ambos extremos (no editable): es notación derivada del tipo, no
   * un valor libre a elegir. */
  function renderFixedManyToManyLabel(pos: { x: number; y: number }) {
    return (
      <div
        className="uml-edge-label uml-edge-label--fixed"
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
  // El panel consolidado edita multiplicidad+rol por extremo solo para los tipos
  // que realmente los tienen editables (ver TYPES_WITHOUT_MULTIPLICITY) y que no
  // sean Muchos-a-muchos (fijo en 0..* por definición, ver renderFixedManyToManyLabel).
  const showEndEditors = showMultiplicity && type !== 'MANY_TO_MANY'

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
  // flecha abierta en target.
  //
  // Asociación: CORRECCIÓN DE NOTACIÓN (antes NUNCA llevaba flecha, ni
  // consultaba isNavigable a propósito -- ver historial). Según UML 2.5.1, la
  // navegabilidad se representa con una flecha abierta simple en el extremo
  // navegable (el mismo símbolo que ya usa Dependencia, arrowOpenId), o sin
  // ningún símbolo si no está definida. El esquema canónico solo tiene
  // `isNavigable` como campo GLOBAL de la relación (no hay navegabilidad
  // separada por extremo), así que no hay forma de saber "cuál" extremo es
  // navegable de forma independiente -- se resuelve con la misma convención que
  // ya usan Generalización/Dependencia: el extremo `target` (el que se dibujó
  // como destino del arrastre de conexión). Esto reemplaza el checkbox "Nav."
  // que antes flotaba siempre visible sobre el canvas (además de ser una
  // notación no estándar, era la pieza que más chocaba visualmente con el resto).
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
  } else if (type === 'ASSOCIATION' && relationship.isNavigable) {
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

        {selected && (
          <div
            className="uml-edge-panel nodrag nopan"
            role="dialog"
            aria-label="Editar relación"
            // Ancla el borde INFERIOR del panel un poco arriba de la línea (en vez de
            // centrarlo con -50%,-50%) y lo deja crecer hacia arriba: así, sin
            // importar cuánto contenido tenga (tipo + hasta 2 extremos + owningSide +
            // navegable), nunca se acerca más a la línea de lo que este offset fijo
            // permite -- la separación no depende de la altura real del panel.
            style={{ transform: `translate(-50%, -100%) translate(${waypoint.x}px, ${waypoint.y - 14}px)` }}
          >
            <div className="uml-edge-panel__row">
              <select
                className="nodrag uml-edge-panel__type-select"
                title="Tipo de relación (UML 2.5.1)"
                value={type}
                onChange={(e) => updateRelationship(relationship.id, { type: e.target.value as RelationshipType })}
              >
                {RELATIONSHIP_TYPE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
              <button
                type="button"
                className="uml-edge-panel__delete nodrag"
                title="Eliminar relación"
                onClick={() => deleteRelationshipAction(relationship.id)}
              >
                ×
              </button>
            </div>

            {showEndEditors && (
              <>
                <div className="uml-edge-panel__row">
                  <span className="uml-edge-panel__tag">Origen</span>
                  <select
                    className="nodrag"
                    value={relationship.sourceMultiplicity ?? '0..1'}
                    onChange={(e) =>
                      updateRelationship(relationship.id, { sourceMultiplicity: e.target.value as Multiplicity })
                    }
                  >
                    {MULTIPLICITY_VALUES.map((m) => (
                      <option key={m} value={m}>
                        {m}
                      </option>
                    ))}
                  </select>
                  <input
                    type="text"
                    className="nodrag uml-edge-panel__role-input"
                    placeholder="rol"
                    title="Rol del extremo origen (opcional)"
                    value={sourceRoleDraft}
                    onChange={(e) => setSourceRoleDraft(e.target.value)}
                    onBlur={commitSourceRole}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') commitSourceRole()
                    }}
                  />
                </div>
                <div className="uml-edge-panel__row">
                  <span className="uml-edge-panel__tag">Destino</span>
                  <select
                    className="nodrag"
                    value={relationship.targetMultiplicity ?? '0..1'}
                    onChange={(e) =>
                      updateRelationship(relationship.id, { targetMultiplicity: e.target.value as Multiplicity })
                    }
                  >
                    {MULTIPLICITY_VALUES.map((m) => (
                      <option key={m} value={m}>
                        {m}
                      </option>
                    ))}
                  </select>
                  <input
                    type="text"
                    className="nodrag uml-edge-panel__role-input"
                    placeholder="rol"
                    title="Rol del extremo destino (opcional)"
                    value={targetRoleDraft}
                    onChange={(e) => setTargetRoleDraft(e.target.value)}
                    onBlur={commitTargetRole}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter') commitTargetRole()
                    }}
                  />
                </div>
              </>
            )}

            <div className="uml-edge-panel__row">
              <select
                className="nodrag"
                title="Lado propietario (owningSide) -- obligatorio para Muchos a muchos"
                value={relationship.owningSide ?? 'SOURCE'}
                onChange={(e) => updateRelationship(relationship.id, { owningSide: e.target.value as OwningSide })}
              >
                <option value="SOURCE">Dueño: origen</option>
                <option value="TARGET">Dueño: destino</option>
              </select>
              <label
                className="uml-edge-panel__navigable"
                title="Navegable -- en reposo se representa con una flecha abierta en el extremo navegable (UML 2.5.1), no con este checkbox"
              >
                <input
                  type="checkbox"
                  checked={relationship.isNavigable ?? true}
                  onChange={(e) => updateRelationship(relationship.id, { isNavigable: e.target.checked })}
                />
                Navegable
              </label>
            </div>
          </div>
        )}

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
