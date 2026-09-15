import { Client, type IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { OperationType, StompBroadcastMessage } from '../types/collaboration'

const BACKEND_HTTP_URL = 'http://localhost:8080'
const BACKEND_WS_URL = `${BACKEND_HTTP_URL}/ws-stomp`

const LOCK_ACQUIRE_TIMEOUT_MS = 2000

export interface DiagramStompHandlers {
  onConnected?: () => void
  onDisconnected?: () => void
  onError?: (message: string) => void
  /** Rechazo puntual de una mutación propia (p.ej. payload inválido), vía /user/queue/errors. */
  onServerError?: (message: string) => void
  onBroadcast: (message: StompBroadcastMessage) => void
  /** Presencia (altas/bajas de sala y movimiento de cursor/selección), ver PresenceMessage. */
  onPresence?: (message: PresenceMessage) => void
  /**
   * Complemento de {@link acquireLock}/{@link releaseLock} para terceros: notifica a
   * TODOS los clientes (no solo al solicitante) cuándo un target queda bloqueado por
   * alguien (para pintar el borde/candado) o liberado (para quitarlo).
   */
  onLockChange?: (info: LockChangeInfo) => void
}

interface LockDeniedMessage {
  targetId: string
  heldBy: string
  expiresInMs: number
}

/** Espejo TS de collaboration/dto/PresenceMessage.java (sección 8.1/RF-04.3). */
export interface PresenceMessage {
  type: 'JOINED' | 'LEFT' | 'CURSOR_UPDATE'
  diagramId: string
  userId: string
  userName: string
  color: string
  cursorX: number | null
  cursorY: number | null
  selectedId: string | null
}

export interface LockAcquiredInfo {
  targetId: string
  userId: string
  userName: string
  color: string
}

export interface LockReleasedInfo {
  targetId: string
  released: true
}

export type LockChangeInfo = LockAcquiredInfo | LockReleasedInfo

/**
 * Cliente STOMP para un diagrama (sección 8 del plan): CONNECT con el JWT como
 * header Authorization (validado por StompChannelInterceptor), suscripción a
 * /topic/diagrams/{id} y envío de mutaciones a /app/diagram/{id}/mutate.
 *
 * <p>También implementa el ciclo de soft-locks (sección 8.1/8.2): las operaciones
 * catalogadas como LOCK_REQUIRED en el backend (RENAME_CLASS, ADD_ATTRIBUTE,
 * UPDATE_ATTRIBUTE, ADD_METHOD, UPDATE_METHOD, etc.) se rechazan en silencio si el
 * emisor no posee un lock vigente sobre el target — {@link acquireLock} debe
 * llamarse (y esperarse) antes de enviar cualquiera de esas mutaciones.</p>
 */
export class DiagramStompClient {
  private client: Client | null = null
  private diagramId: string | null = null
  private pendingLocks = new Map<string, (granted: boolean) => void>()

  connect(diagramId: string, token: string, handlers: DiagramStompHandlers) {
    this.disconnect()
    this.diagramId = diagramId

    const client = new Client({
      webSocketFactory: () => new SockJS(BACKEND_WS_URL) as WebSocket,
      connectHeaders: {
        Authorization: `Bearer ${token}`,
      },
      reconnectDelay: 4000,
      debug: () => {},
      onConnect: () => {
        handlers.onConnected?.()
        client.subscribe(`/topic/diagrams/${diagramId}`, (message: IMessage) => {
          let body: Record<string, unknown>
          try {
            body = JSON.parse(message.body)
          } catch (err) {
            console.error('No se pudo parsear el mensaje STOMP', err)
            return
          }

          // LOCK_ACQUIRED se difunde a este mismo topic (LockMessage.LockAcquired del
          // backend), distinguible de un StompBroadcastMessage real porque no trae
          // operationType/sequenceNum y sí un ttl numérico.
          if (typeof body.ttl === 'number' && typeof body.operationType !== 'string') {
            this.resolveLock(body.targetId as string, true)
            handlers.onLockChange?.({
              targetId: body.targetId as string,
              userId: body.userId as string,
              userName: body.userName as string,
              color: body.color as string,
            })
            return
          }

          // PresenceMessage (JOINED/LEFT/CURSOR_UPDATE) llega por el mismo topic y se
          // distingue por su campo "type" literal (ni StompBroadcastMessage ni los
          // mensajes de lock lo tienen).
          if (
            typeof body.type === 'string' &&
            (body.type === 'JOINED' || body.type === 'LEFT' || body.type === 'CURSOR_UPDATE')
          ) {
            handlers.onPresence?.(body as unknown as PresenceMessage)
            return
          }

          // Un StompBroadcastMessage real SIEMPRE trae operationType (string) y
          // sequenceNum (number). LOCK_RELEASED (LockMessage.LockReleased, forma
          // {targetId}) llega por este mismo topic y no tiene ninguno de los dos: sin
          // este filtro, terminaba pasando igual a onBroadcast con operationType
          // undefined -- applyOperation lo ignoraba en su rama default (inofensivo),
          // pero es más correcto no reenviarlo como si fuera una mutación real.
          if (typeof body.operationType !== 'string' || typeof body.sequenceNum !== 'number') {
            if (typeof body.targetId === 'string') {
              handlers.onLockChange?.({ targetId: body.targetId, released: true })
            }
            return
          }

          handlers.onBroadcast(body as unknown as StompBroadcastMessage)
        })
        // Rechazos puntuales del emisor (ver CollaborationStompController.requireOwnedLock
        // y applyAndBroadcast): sin esto, una mutación rechazada por el backend (payload
        // inválido, lock ajeno, etc.) desaparece en silencio del lado del cliente.
        client.subscribe('/user/queue/errors', (message: IMessage) => {
          handlers.onServerError?.(message.body)
        })
        // LOCK_DENIED (LockMessage.LockDenied): respuesta privada de fallo rápido cuando
        // el recurso ya está tomado por otro usuario (sección 8.2, sin cola de espera).
        client.subscribe('/user/queue/locks', (message: IMessage) => {
          try {
            const body = JSON.parse(message.body) as LockDeniedMessage
            this.resolveLock(body.targetId, false)
          } catch (err) {
            console.error('No se pudo parsear la respuesta de lock', err)
          }
        })
      },
      onStompError: (frame) => {
        handlers.onError?.(frame.headers.message ?? 'Error STOMP desconocido')
      },
      onWebSocketClose: () => {
        handlers.onDisconnected?.()
      },
    })

    client.activate()
    this.client = client
  }

  disconnect() {
    this.client?.deactivate()
    this.client = null
    this.diagramId = null
    this.pendingLocks.clear()
  }

  sendMutation(operationType: OperationType, targetId: string | null, userId: string, payload: Record<string, unknown>) {
    if (!this.client?.connected || !this.diagramId) {
      console.warn('STOMP no conectado; se descarta la mutación', operationType)
      return
    }

    this.client.publish({
      destination: `/app/diagram/${this.diagramId}/mutate`,
      body: JSON.stringify({
        operationType,
        targetId,
        userId,
        clientTimestamp: Date.now(),
        payload,
      }),
    })
  }

  /**
   * Da de alta al usuario en la sala de presencia del diagrama (RF-04.3). Debe
   * llamarse una única vez, después de {@code onConnected}, para que el backend
   * registre nombre/color y pueda difundir JOINED al resto de la sala.
   */
  joinPresence(diagramId: string, userId: string, userName: string, color: string) {
    if (!this.client?.connected) return
    this.client.publish({
      destination: `/app/diagram/${diagramId}/presence/join`,
      body: JSON.stringify({ userId, userName, color }),
    })
  }

  /**
   * Difunde la posición del cursor (en coordenadas de "flow", ya transformadas por
   * el emisor) y, opcionalmente, el elemento actualmente seleccionado. Pensado para
   * throttlear en el llamador (p.ej. cada 50-80ms) y no saturar la red.
   */
  sendCursorUpdate(
    diagramId: string,
    userId: string,
    userName: string,
    color: string,
    x: number,
    y: number,
    selectedId: string | null,
  ) {
    if (!this.client?.connected) return
    this.client.publish({
      destination: `/app/diagram/${diagramId}/cursor`,
      body: JSON.stringify({ userId, userName, color, x, y, selectedId }),
    })
  }

  /**
   * Adquiere el soft-lock de {@code targetId} antes de enviar una mutación
   * LOCK_REQUIRED. Resuelve {@code true} si el backend difunde LOCK_ACQUIRED,
   * {@code false} si responde LOCK_DENIED o si no hay respuesta dentro del timeout
   * (fallo rápido: nunca deja al usuario esperando indefinidamente, ver sección 8.2).
   */
  acquireLock(targetId: string, userId: string): Promise<boolean> {
    if (!this.client?.connected || !this.diagramId) {
      return Promise.resolve(false)
    }

    const diagramId = this.diagramId
    return new Promise((resolve) => {
      const timeout = setTimeout(() => {
        this.resolveLock(targetId, false)
      }, LOCK_ACQUIRE_TIMEOUT_MS)
      this.pendingLocks.set(targetId, (granted) => {
        clearTimeout(timeout)
        resolve(granted)
      })

      this.client!.publish({
        destination: `/app/diagram/${diagramId}/lock`,
        body: JSON.stringify({ action: 'ACQUIRE', targetId, userId }),
      })
    })
  }

  releaseLock(targetId: string, userId: string) {
    if (!this.client?.connected || !this.diagramId) return
    this.client.publish({
      destination: `/app/diagram/${this.diagramId}/lock`,
      body: JSON.stringify({ action: 'RELEASE', targetId, userId }),
    })
  }

  private resolveLock(targetId: string, granted: boolean) {
    const resolver = this.pendingLocks.get(targetId)
    if (resolver) {
      this.pendingLocks.delete(targetId)
      resolver(granted)
    }
  }
}

export const diagramStompClient = new DiagramStompClient()
