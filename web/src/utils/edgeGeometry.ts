// Compartido por UmlRelationshipEdge/ErRelationshipEdge -- patrón estándar
// "Floating Edges" de React Flow (intersección rayo-rectángulo desde el
// centro del nodo), adaptado para que el punto de conexión apunte hacia el
// waypoint cuando existe uno, no solo hacia el otro nodo. Antes cada clase
// tenía un único handle fijo (target=Left, source=Right, ver
// UmlClassNode.tsx/ErTableNode.tsx) y el edge usaba directamente las
// coordenadas de ESE handle fijo sin importar hacia dónde se moviera el
// waypoint o dónde quedara el otro extremo -- la línea podía terminar
// entrando por el lado "equivocado" y atravesando la caja por encima del
// contenido. Este módulo recalcula el punto real sobre el borde en cada
// render, en vez de depender de qué handle disparó la conexión original.
import { useStore, Position } from 'reactflow'

export interface Rect {
  x: number
  y: number
  width: number
  height: number
}

export interface Point {
  x: number
  y: number
}

export function rectCenter(rect: Rect): Point {
  return { x: rect.x + rect.width / 2, y: rect.y + rect.height / 2 }
}

/**
 * Punto donde el segmento [centro del rectángulo -> targetPoint] cruza el
 * borde del rectángulo. Si targetPoint coincide con el centro (caso
 * degenerado, dos nodos exactamente superpuestos), devuelve el centro tal
 * cual -- no hay una dirección real que intersectar.
 */
export function getRectIntersection(rect: Rect, targetPoint: Point): Point {
  const center = rectCenter(rect)
  const dx = targetPoint.x - center.x
  const dy = targetPoint.y - center.y
  if (dx === 0 && dy === 0) return center

  const halfW = rect.width / 2
  const halfH = rect.height / 2
  const scaleX = dx !== 0 ? halfW / Math.abs(dx) : Number.POSITIVE_INFINITY
  const scaleY = dy !== 0 ? halfH / Math.abs(dy) : Number.POSITIVE_INFINITY
  const scale = Math.min(scaleX, scaleY)

  return { x: center.x + dx * scale, y: center.y + dy * scale }
}

/** A qué lado del rectángulo pertenece un punto ya sobre su borde (para orientar
 * markers/curvas de smoothstep con el mismo criterio que React Flow usa
 * internamente para Position.Top/Right/Bottom/Left). */
export function getRectSide(rect: Rect, point: Point): Position {
  const center = rectCenter(rect)
  const dx = point.x - center.x
  const dy = point.y - center.y
  const halfW = rect.width / 2 || 1
  const halfH = rect.height / 2 || 1
  // Compara cuánto "sobrepasa" el punto en cada eje relativo al semieje
  // correspondiente -- el eje con mayor sobrepaso relativo es el lado real.
  const overX = Math.abs(dx) / halfW
  const overY = Math.abs(dy) / halfH
  if (overX >= overY) return dx >= 0 ? Position.Right : Position.Left
  return dy >= 0 ? Position.Bottom : Position.Top
}

function rectsEqual(a: Rect | null, b: Rect | null): boolean {
  if (a === b) return true
  if (!a || !b) return false
  return a.x === b.x && a.y === b.y && a.width === b.width && a.height === b.height
}

/**
 * Rectángulo absoluto (en coordenadas del flow, no de pantalla) de un nodo, leído
 * en vivo del store interno de reactflow -- NO de los props sourceX/Y/targetX/Y
 * del edge, que solo reflejan el handle fijo que originó la conexión (ver
 * comentario de cabecera de este archivo). `nodeInternals` es la única vía
 * confirmada en esta versión instalada (11.11.4): no existe `useInternalNode`
 * acá (llegó en una versión posterior de reactflow), así que se lee directo del
 * Map interno. Devuelve null mientras reactflow todavía no midió el nodo
 * (primer render, antes de que el ResizeObserver interno corra) -- el llamador
 * debe caer de vuelta a los props sourceX/Y/targetX/Y en ese caso transitorio.
 */
export function useNodeRect(nodeId: string | undefined): Rect | null {
  return useStore((state) => {
    if (!nodeId) return null
    const node = state.nodeInternals.get(nodeId)
    if (!node || !node.positionAbsolute || !node.width || !node.height) return null
    return {
      x: node.positionAbsolute.x,
      y: node.positionAbsolute.y,
      width: node.width,
      height: node.height,
    }
  }, rectsEqual)
}
