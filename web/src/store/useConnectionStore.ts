// Estado GLOBAL de la conexión de colaboración en tiempo real (STOMP/WebSocket).
// Hoy (ver src/App.tsx) el estado 'connecting' | 'online' | 'offline' vive en un
// useState LOCAL a App.tsx y no es accesible desde otros componentes (p. ej.
// OfflineSyncBanner). Este store lo expone globalmente y agrega 'syncing' para
// una fase posterior (RF-05: cola offline + reconciliación), que todavía no lo
// dispara automáticamente pero que ya puede apoyarse en este tipo/estado.
import { create } from 'zustand'

export type ConnectionStatus = 'connecting' | 'online' | 'offline' | 'syncing'

interface ConnectionState {
  /** Estado de la conexión STOMP propiamente dicha (lo escribe App.tsx). */
  status: ConnectionStatus
  setStatus: (status: ConnectionStatus) => void
  /** Timestamp (Date.now()) de la última vez que status pasó a 'online'. */
  lastConnectedAt: number | null
  /** Timestamp (Date.now()) de la última vez que status pasó a 'offline'. */
  lastDisconnectedAt: number | null
  /**
   * Señal AUXILIAR de conectividad de RED del navegador (navigator.onLine /
   * eventos 'online'/'offline' de window). Es informativa: puede desincronizarse
   * del estado STOMP real (WiFi activo pero servidor caído, o viceversa un
   * STOMP con reconnectDelay reintentando en segundo plano). NO reemplaza a
   * `status`, que sigue siendo la fuente de verdad de la colaboración.
   */
  browserOnline: boolean
  setBrowserOnline: (online: boolean) => void
}

export const useConnectionStore = create<ConnectionState>((set) => ({
  status: 'connecting',
  // Reglas de transición (simples a propósito, sin máquina de estados formal):
  // - 'online'  -> registra lastConnectedAt (útil para mostrar "reconectado hace X").
  // - 'offline' -> registra lastDisconnectedAt.
  // - 'connecting' / 'syncing' -> no tocan los timestamps, son estados transitorios.
  setStatus: (status) =>
    set((state) => ({
      status,
      lastConnectedAt: status === 'online' ? Date.now() : state.lastConnectedAt,
      lastDisconnectedAt: status === 'offline' ? Date.now() : state.lastDisconnectedAt,
    })),
  lastConnectedAt: null,
  lastDisconnectedAt: null,
  browserOnline: typeof navigator !== 'undefined' ? navigator.onLine : true,
  setBrowserOnline: (online) => set({ browserOnline: online }),
}))

// Efecto de inicialización opcional: mantiene `browserOnline` sincronizado con
// los eventos nativos del navegador. Es un dato auxiliar/informativo -- no
// dispara transiciones de `status`, que siguen viniendo exclusivamente de
// setStatus() invocado desde los callbacks de stompClient en App.tsx.
if (typeof window !== 'undefined') {
  window.addEventListener('online', () => useConnectionStore.getState().setBrowserOnline(true))
  window.addEventListener('offline', () => useConnectionStore.getState().setBrowserOnline(false))
}
