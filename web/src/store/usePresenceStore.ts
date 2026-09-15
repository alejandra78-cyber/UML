import { create } from 'zustand'
import type { PresenceMessage } from '../collaboration/stompClient'

/** Cursor remoto en coordenadas de "flow" (ya transformadas por el emisor con
 * screenToFlowPosition), listas para posicionar un div superpuesto sobre el
 * canvas que el pan/zoom reposiciona correctamente (ver CollaboratorPresence). */
export interface RemoteCursor {
  userName: string
  color: string
  x: number
  y: number
  selectedId: string | null
}

/** Soft-lock activo de OTRO usuario sobre un elemento (sección 8.2). */
export interface ActiveLock {
  userId: string
  userName: string
  color: string
}

interface PresenceState {
  /** userId del usuario local: nunca se muestra su propio cursor/lock a sí mismo. */
  currentUserId: string | null
  remoteCursors: Record<string, RemoteCursor>
  activeLocks: Record<string, ActiveLock>

  setCurrentUserId: (userId: string) => void
  /** Despacha un PresenceMessage (JOINED/LEFT/CURSOR_UPDATE) recibido por STOMP. */
  applyPresenceMessage: (message: PresenceMessage) => void
  /** Pinta el borde de lock de `targetId` a nombre de otro usuario (ignora locks propios). */
  setLock: (targetId: string, userId: string, userName: string, color: string) => void
  /** Limpia el lock de `targetId` (LOCK_RELEASED, TTL expirado o release explícito). */
  clearLock: (targetId: string) => void
  /** Limpia cursor y locks de un usuario que se desconectó (LEFT) o cuyos locks
   * expiraron del lado servidor: el cliente debe reflejarlo igual. */
  clearForUser: (userId: string) => void
}

export const usePresenceStore = create<PresenceState>((set, get) => ({
  currentUserId: null,
  remoteCursors: {},
  activeLocks: {},

  setCurrentUserId: (userId) => set({ currentUserId: userId }),

  applyPresenceMessage: (message) => {
    const { currentUserId } = get()
    const { userId } = message
    if (!userId || userId === currentUserId) return

    if (message.type === 'LEFT') {
      get().clearForUser(userId)
      return
    }

    if (message.type === 'CURSOR_UPDATE') {
      if (typeof message.cursorX !== 'number' || typeof message.cursorY !== 'number') return
      set((state) => ({
        remoteCursors: {
          ...state.remoteCursors,
          [userId]: {
            userName: message.userName,
            color: message.color,
            x: message.cursorX as number,
            y: message.cursorY as number,
            selectedId: message.selectedId ?? null,
          },
        },
      }))
      return
    }

    // JOINED: solo confirma el alta de sala; el cursor aparece recién con el
    // primer CURSOR_UPDATE (evita mostrar un cursor fantasma en 0,0).
  },

  setLock: (targetId, userId, userName, color) => {
    if (userId === get().currentUserId) return
    set((state) => ({
      activeLocks: { ...state.activeLocks, [targetId]: { userId, userName, color } },
    }))
  },

  clearLock: (targetId) => {
    set((state) => {
      if (!(targetId in state.activeLocks)) return state
      const nextLocks = { ...state.activeLocks }
      delete nextLocks[targetId]
      return { activeLocks: nextLocks }
    })
  },

  clearForUser: (userId) => {
    set((state) => {
      const nextCursors = { ...state.remoteCursors }
      delete nextCursors[userId]

      const nextLocks = { ...state.activeLocks }
      for (const [targetId, lock] of Object.entries(nextLocks)) {
        if (lock.userId === userId) delete nextLocks[targetId]
      }

      return { remoteCursors: nextCursors, activeLocks: nextLocks }
    })
  },
}))
