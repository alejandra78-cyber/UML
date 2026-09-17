import { usePendingMutationCount } from '../../offline/offlineQueue'
import { useConnectionStore } from '../../store/useConnectionStore'
import { useDiagramStore } from '../../store/useDiagramStore'
import './OfflineSyncBanner.css'

// PKG-04 Resiliencia Offline — implementa UC11 (Trabajar sin Conexión) y UC12
// (Sincronizar Cambios al Reconectar)
/**
 * Indicador visual del estado de la conexión de colaboración en tiempo real
 * (PLAN_ARQUITECTONICO.md: OfflineSyncBanner -- Online / Offline / Sincronizando).
 *
 * Ahora que existe una cola real en IndexedDB (ver offline/offlineQueue.ts), el
 * banner deja de ser SOLO un indicador de estado de conexión: mientras está
 * offline también muestra cuántas mutaciones quedaron encoladas, para que el
 * usuario sepa que sus cambios están a salvo (y no solo "descartados en
 * silencio", como pasaba antes de esta cola).
 *
 * Lee useConnectionStore y useDiagramStore -- no requiere props ni cambios
 * estructurales en el árbol de componentes. Se monta una única vez en cualquier
 * parte de la app (posicionamiento `fixed`, no participa del layout).
 */
export function OfflineSyncBanner() {
  const status = useConnectionStore((state) => state.status)
  const diagramId = useDiagramStore((state) => state.diagramId)
  const pendingCount = usePendingMutationCount(diagramId)

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
        Sincronizando{pendingCount > 0 ? ` — ${pendingCount} ${pendingCount === 1 ? 'cambio' : 'cambios'}…` : '…'}
      </div>
    )
  }

  // status === 'offline'
  return (
    <div className="offline-sync-banner offline-sync-banner--offline" role="alert">
      <span className="offline-sync-banner__dot" />
      {pendingCount > 0
        ? `Sin conexión — ${pendingCount} ${pendingCount === 1 ? 'cambio pendiente' : 'cambios pendientes'} de sincronizar`
        : 'Sin conexión — los cambios se guardan localmente hasta reconectar'}
    </div>
  )
}
