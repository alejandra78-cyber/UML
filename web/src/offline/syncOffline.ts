// PKG-04 Resiliencia Offline -- implementa UC12 (Sincronizar Cambios al Reconectar).
import { authFetch } from '../collaboration/diagramBootstrap'
import type { QueuedMutation } from './offlineQueue'

export interface SyncReport {
  appliedCount: number
  discardedCount: number
  discardedDetails: string[]
}

/** Forma exacta que espera el backend en POST /sync-offline (OfflineSyncRequest). */
interface SyncOfflineOperation {
  clientMutationId: string
  operationType: string
  payload: Record<string, unknown>
  clientTimestamp: number
}

// Conectado de verdad (backend UC12 cerrado y verificado). El DTO del backend
// (OfflineSyncRequest) NO tiene un campo targetId separado -- la convención
// acordada es incluirlo como una clave más DENTRO de payload cuando la operación
// lo necesita (mismo significado que el targetId que ya viaja aparte por STOMP en
// tiempo real: classId contenedor en ADD_ATTRIBUTE, attributeId en
// UPDATE_ATTRIBUTE, etc.). ADD_CLASS/ADD_RELATIONSHIP ya encolan targetId: null
// (ver useDiagramStore.ts), así que quedan afuera de este merge naturalmente.
export async function syncOfflineQueue(diagramId: string, token: string, queue: QueuedMutation[]): Promise<SyncReport> {
  const operations: SyncOfflineOperation[] = queue.map((mutation) => ({
    clientMutationId: mutation.id,
    operationType: mutation.operationType,
    payload: mutation.targetId !== null ? { ...mutation.payload, targetId: mutation.targetId } : mutation.payload,
    clientTimestamp: mutation.queuedAt,
  }))

  const res = await authFetch(token, `/diagrams/${diagramId}/sync-offline`, {
    method: 'POST',
    body: JSON.stringify({ operations }),
  })
  if (!res.ok) {
    throw new Error(`No se pudo sincronizar los cambios offline (${res.status})`)
  }
  return res.json()
}
