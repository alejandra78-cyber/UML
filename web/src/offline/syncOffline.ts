// PKG-04 Resiliencia Offline -- implementa UC12 (Sincronizar Cambios al Reconectar).
import { authFetch } from '../collaboration/diagramBootstrap'
import type { QueuedMutation } from './offlineQueue'

export interface SyncReport {
  appliedCount: number
  discardedCount: number
  discardedDetails: string[]
}

/** Forma exacta que espera el backend en POST /sync-offline (OfflineSyncRequest).
 * `payload` es un STRING (JSON serializado), no un objeto anidado -- confirmado
 * con el error real del backend la primera vez que esta cola se pobló de verdad
 * (antes de agregar la detección de caída por evento 'offline'/'online', el
 * offline queue nunca llegaba a poblarse en un corte de red real, así que este
 * código nunca se había ejercitado contra el backend real):
 * `Cannot deserialize value of type java.lang.String from Object value
 * (token JsonToken.START_OBJECT)` en
 * `OfflineSyncRequest["operations"]->[0]->PendingOperation["payload"]`. El
 * campo `payload` del lado del backend es un `String` (blob JSON que el
 * propio backend parsea después), no un objeto tipado en el DTO de entrada.
 */
interface SyncOfflineOperation {
  clientMutationId: string
  operationType: string
  payload: string
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
    payload: JSON.stringify(
      mutation.targetId !== null ? { ...mutation.payload, targetId: mutation.targetId } : mutation.payload,
    ),
    clientTimestamp: mutation.queuedAt,
  }))

  const res = await authFetch(token, `/diagrams/${diagramId}/sync-offline`, {
    method: 'POST',
    body: JSON.stringify({ operations }),
  })
  if (!res.ok) {
    // El backend puede mandar el detalle del error en el cuerpo -- se muestra
    // tal cual si viene, en vez de un genérico sin información.
    const detail = await res.text().catch(() => '')
    throw new Error(detail ? `No se pudo sincronizar los cambios offline: ${detail}` : `No se pudo sincronizar los cambios offline (${res.status})`)
  }
  return res.json()
}
