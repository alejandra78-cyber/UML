import { useCallback, useState } from 'react'
import { fetchSnapshot } from '../../collaboration/diagramBootstrap'
import { clearQueue, getQueuedMutations, type QueuedMutation } from '../../offline/offlineQueue'
import { syncOfflineQueue, type SyncReport } from '../../offline/syncOffline'
import type { ConnectionStatus } from '../../store/useConnectionStore'
import { useDiagramStore } from '../../store/useDiagramStore'
import type { CanonicalModel } from '../../types/diagram'

// PKG-04 Resiliencia Offline -- implementa UC12 (Sincronizar Cambios al Reconectar).
/**
 * Orquesta lo que pasa cada vez que el STOMP client se conecta (primera conexión
 * de la sesión O cualquier reconexión automática posterior -- ver `reconnectDelay`
 * en collaboration/stompClient.ts, que ya reintenta solo tras una caída; cada
 * reintento exitoso vuelve a disparar `onConnected`, el gancho natural para esto).
 *
 * Vive en un hook aparte (en vez de inline en App.tsx) a propósito: App.tsx es un
 * archivo compartido con otro trabajo en paralelo sobre la misma barra superior, y
 * esto mantiene el diff ahí a solo un par de líneas (ver App.tsx: se invoca el
 * hook y se llama a `handleReconnect` desde dentro de `onConnected`).
 *
 * `handleReconnect` recibe `diagramId`/`token` como PARÁMETROS explícitos en vez de
 * argumentos fijos del hook (p.ej. `useOfflineSync(diagramId, token)`) a propósito:
 * en App.tsx esos valores ya están resueltos como variables locales dentro del
 * efecto principal (justo después de `ensureActiveDiagram`, antes de llamar a
 * `diagramStompClient.connect`) y siguen vigentes en el closure de `onConnected`.
 * Si en cambio el hook dependiera de props/estado reactivo, el PRIMER
 * `onConnected` de la sesión podría dispararse antes de que React re-renderice
 * con esos valores ya propagados -- y ese primer connect es justo el caso más
 * importante a cubrir: recargar la página con una cola pendiente de una sesión
 * offline anterior.
 *
 * `setConnectionStatus` se recibe como parámetro (en vez de leer/escribir
 * useConnectionStore directamente acá adentro) porque App.tsx ya mantiene un
 * useState local espejado con el store global vía su propio
 * `updateConnectionStatus` (para el badge "Conectado/Conectando…/Desconectado"
 * de la barra superior) -- reusar exactamente esa función evita que ambos
 * estados (local y global) se desincronicen durante 'syncing'.
 */
export function useOfflineSync(setConnectionStatus: (status: ConnectionStatus) => void) {
  const [reconciliationReport, setReconciliationReport] = useState<SyncReport | null>(null)
  const hydrate = useDiagramStore((state) => state.hydrate)

  const handleReconnect = useCallback(
    async (diagramId: string, token: string, connectNow: () => void) => {
      let queue: QueuedMutation[] = []
      try {
        queue = await getQueuedMutations(diagramId)
      } catch (err) {
        console.error('No se pudo leer la cola offline de IndexedDB', err)
      }

      if (queue.length === 0) {
        // Caso simple (igual que antes de que existiera esta cola): nada
        // pendiente, se pasa directo a online.
        setConnectionStatus('online')
        connectNow()
        return
      }

      setConnectionStatus('syncing')
      try {
        const report = await syncOfflineQueue(diagramId, token, queue)
        await clearQueue(diagramId)
        setReconciliationReport(report)

        // Mientras estuvimos offline, otros colaboradores pudieron haber seguido
        // editando el mismo diagrama: se vuelve a pedir el snapshot fresco en vez
        // de confiar en el estado local, que solo conoce nuestros propios cambios
        // optimistas (y los que ya llegaron por broadcast antes de desconectarnos).
        const snapshot = await fetchSnapshot(token, diagramId)
        const parsed = JSON.parse(snapshot.currentState) as Partial<CanonicalModel>
        hydrate({
          schemaVersion: parsed.schemaVersion ?? '1.0.0',
          mutationVersion: parsed.mutationVersion ?? 1,
          classes: parsed.classes ?? [],
          relationships: parsed.relationships ?? [],
        })
      } catch (err) {
        // Fallo de red/servidor durante la sincronización: la cola NO se vació
        // (clearQueue solo corrió si syncOfflineQueue resolvió), así que las
        // mutaciones siguen persistidas en IndexedDB y se reintentarán en la
        // próxima reconexión exitosa.
        console.error('Falló la sincronización de cambios offline', err)
      } finally {
        setConnectionStatus('online')
        connectNow()
      }
    },
    [setConnectionStatus, hydrate],
  )

  return {
    reconciliationReport,
    dismissReport: () => setReconciliationReport(null),
    handleReconnect,
  }
}
