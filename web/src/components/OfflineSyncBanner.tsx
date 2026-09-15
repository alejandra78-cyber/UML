import { useConnectionStore } from '../store/useConnectionStore'
import './OfflineSyncBanner.css'

/**
 * Indicador visual del estado de la conexión de colaboración en tiempo real
 * (PLAN_ARQUITECTONICO.md: OfflineSyncBanner -- Online / Offline / Sincronizando).
 *
 * Alcance de esta fase: SOLO el indicador visual de los 3(+1) estados. La cola
 * offline, IndexedDB, el rebase de operaciones y el modal de reporte de
 * reconciliación son de una fase posterior (RF-05) y no se implementan aquí.
 *
 * Lee useConnectionStore -- no requiere props ni cambios estructurales en el
 * árbol de componentes. Se monta una única vez en cualquier parte de la app
 * (posicionamiento `fixed`, no participa del layout).
 */
export function OfflineSyncBanner() {
  const status = useConnectionStore((state) => state.status)

  if (status === 'online') {
    // Caso simple: todo funciona, no se muestra ningún banner persistente.
    return null
  }

  if (status === 'connecting') {
    return (
      <div className="offline-sync-banner offline-sync-banner--connecting" role="status">
        <span className="offline-sync-banner__dot" />
        Conectando…
      </div>
    )
  }

  if (status === 'syncing') {
    return (
      <div className="offline-sync-banner offline-sync-banner--syncing" role="status">
        <span className="offline-sync-banner__dot" />
        Sincronizando…
      </div>
    )
  }

  // status === 'offline'
  return (
    <div className="offline-sync-banner offline-sync-banner--offline" role="alert">
      <span className="offline-sync-banner__dot" />
      Sin conexión — los cambios no se están sincronizando
    </div>
  )
}
