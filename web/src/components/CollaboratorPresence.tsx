import { useViewport } from 'reactflow'
import { usePresenceStore } from '../store/usePresenceStore'
import './CollaboratorPresence.css'

/**
 * Cursores remotos flotantes (RF-04.3): un div/SVG por colaborador conectado,
 * posicionado sobre el canvas a partir de sus coordenadas de "flow" (ya
 * transformadas por el emisor con screenToFlowPosition) proyectadas a pantalla
 * con el viewport actual (pan/zoom), para que se reposicionen correctamente al
 * mover/hacer zoom en el lienzo.
 *
 * Debe montarse como hijo de <ReactFlow> (junto a <Background />/<Controls />),
 * ver instrucciones de integración en el reporte de la tarea.
 */
export function CollaboratorPresence() {
  const remoteCursors = usePresenceStore((state) => state.remoteCursors)
  const { x: viewportX, y: viewportY, zoom } = useViewport()

  const entries = Object.entries(remoteCursors)
  if (entries.length === 0) return null

  return (
    <div className="collaborator-presence-layer">
      {entries.map(([userId, cursor]) => {
        const screenX = cursor.x * zoom + viewportX
        const screenY = cursor.y * zoom + viewportY
        return (
          <div
            key={userId}
            className="collaborator-cursor"
            style={{ transform: `translate(${screenX}px, ${screenY}px)` }}
          >
            <svg
              className="collaborator-cursor__arrow"
              width="20"
              height="20"
              viewBox="0 0 20 20"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                d="M2 1 L2 16.5 L6.2 13 L8.8 18.5 L11.4 17.2 L8.8 11.8 L14.5 11.8 Z"
                fill={cursor.color}
                stroke="#ffffff"
                strokeWidth="1"
                strokeLinejoin="round"
              />
            </svg>
            <span className="collaborator-cursor__label" style={{ background: cursor.color }}>
              {cursor.userName}
            </span>
          </div>
        )
      })}
    </div>
  )
}
