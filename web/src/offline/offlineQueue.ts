// PKG-04 Resiliencia Offline -- implementa UC11 (Trabajar sin Conexión).
//
// Cola de persistencia REAL para las mutaciones que useDiagramStore genera
// mientras `transport === null` (STOMP desconectado). Antes de este módulo esas
// mutaciones se aplicaban solo en memoria (Zustand) y se perdían sin dejar
// rastro al recargar la página -- ver el comentario (ya obsoleto) en
// OfflineSyncBanner.tsx que admitía que "la cola offline, IndexedDB... no se
// implementan aquí".
//
// Se usa la API nativa `indexedDB` del navegador directamente (sin agregar
// dependencias nuevas): una base `modelcollab-offline-queue` con un único
// object store `mutations` (keyPath: 'id'), indexado por `diagramId` para poder
// leer/borrar solo las mutaciones del diagrama activo sin recorrer toda la base
// (útil si el usuario navegó por varios diagramas en distintas sesiones offline).
import { useEffect } from 'react'
import { create } from 'zustand'
import type { OperationType } from '../types/collaboration'

export interface QueuedMutation {
  id: string
  diagramId: string
  operationType: OperationType
  targetId: string | null
  payload: Record<string, unknown>
  queuedAt: number
}

const DB_NAME = 'modelcollab-offline-queue'
const DB_VERSION = 1
const STORE_NAME = 'mutations'
const DIAGRAM_INDEX = 'diagramId'

function openDatabase(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      const db = request.result
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        const store = db.createObjectStore(STORE_NAME, { keyPath: 'id' })
        store.createIndex(DIAGRAM_INDEX, DIAGRAM_INDEX, { unique: false })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error)
  })
}

/**
 * Encola una mutación real para reenviar al backend cuando vuelva la conexión.
 * `id`/`queuedAt` los genera esta función -- el llamador (useDiagramStore) solo
 * conoce operationType/targetId/payload/diagramId, igual que le pasaría a
 * `transport.send(...)` si hubiera transport disponible.
 */
export async function enqueueMutation(mutation: Omit<QueuedMutation, 'id' | 'queuedAt'>): Promise<void> {
  const record: QueuedMutation = {
    ...mutation,
    id: crypto.randomUUID(),
    queuedAt: Date.now(),
  }
  try {
    const db = await openDatabase()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      tx.objectStore(STORE_NAME).put(record)
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
    db.close()
  } catch (err) {
    // IndexedDB puede fallar (modo privado en algunos navegadores, cuota llena,
    // etc.): se registra el error pero no se relanza -- igual que el resto del
    // proyecto trata a localStorage (ver diagramBootstrap.ts), preferimos
    // degradar con una advertencia antes que romper la UI. La mutación en
    // memoria (Zustand) ya se aplicó de todos modos.
    console.error('No se pudo encolar la mutación offline en IndexedDB', err)
    return
  }
  await refreshCount(mutation.diagramId)
}

/** Devuelve las mutaciones encoladas de un diagrama, ordenadas por `queuedAt` ascendente (orden de reenvío). */
export async function getQueuedMutations(diagramId: string): Promise<QueuedMutation[]> {
  try {
    const db = await openDatabase()
    const list = await new Promise<QueuedMutation[]>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly')
      const index = tx.objectStore(STORE_NAME).index(DIAGRAM_INDEX)
      const request = index.getAll(IDBKeyRange.only(diagramId))
      request.onsuccess = () => resolve(request.result as QueuedMutation[])
      request.onerror = () => reject(request.error)
    })
    db.close()
    return list.sort((a, b) => a.queuedAt - b.queuedAt)
  } catch (err) {
    console.error('No se pudo leer la cola offline de IndexedDB', err)
    return []
  }
}

/** Vacía la cola de un diagrama (después de sincronizar con éxito). */
export async function clearQueue(diagramId: string): Promise<void> {
  try {
    const db = await openDatabase()
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite')
      const index = tx.objectStore(STORE_NAME).index(DIAGRAM_INDEX)
      const request = index.openCursor(IDBKeyRange.only(diagramId))
      request.onsuccess = () => {
        const cursor = request.result
        if (cursor) {
          cursor.delete()
          cursor.continue()
        }
      }
      tx.oncomplete = () => resolve()
      tx.onerror = () => reject(tx.error)
    })
    db.close()
  } catch (err) {
    console.error('No se pudo vaciar la cola offline de IndexedDB', err)
    return
  }
  await refreshCount(diagramId)
}

// --- Contador reactivo -------------------------------------------------------
// IndexedDB no es reactivo por sí solo: OfflineSyncBanner necesita saber "cuántas
// mutaciones hay encoladas AHORA MISMO" en vivo, no solo al montar. En vez de
// agregar una librería nueva, se usa un store de Zustand liviano (mismo patrón
// que el resto del proyecto) que enqueueMutation/clearQueue actualizan cada vez
// que tocan la base, indexado por diagramId (un usuario puede, en teoría, haber
// dejado colas de sesiones offline previas en otros diagramas).

interface OfflineQueueCountState {
  counts: Record<string, number>
  setCount: (diagramId: string, count: number) => void
}

const useOfflineQueueCountStore = create<OfflineQueueCountState>((set) => ({
  counts: {},
  setCount: (diagramId, count) => set((state) => ({ counts: { ...state.counts, [diagramId]: count } })),
}))

async function refreshCount(diagramId: string): Promise<void> {
  const list = await getQueuedMutations(diagramId)
  useOfflineQueueCountStore.getState().setCount(diagramId, list.length)
}

/**
 * Hook reactivo: cantidad de mutaciones encoladas para `diagramId` (0 si no hay
 * diagramId todavía, p.ej. antes de que termine ensureActiveDiagram). Al montar
 * dispara una lectura inicial desde IndexedDB (por si ya había una cola
 * persistida de una sesión offline anterior a esta carga de página); de ahí en
 * más se mantiene al día en vivo porque enqueueMutation/clearQueue actualizan el
 * mismo store de Zustand.
 */
export function usePendingMutationCount(diagramId: string | null): number {
  const count = useOfflineQueueCountStore((state) => (diagramId ? (state.counts[diagramId] ?? 0) : 0))

  useEffect(() => {
    if (!diagramId) return
    let cancelled = false
    getQueuedMutations(diagramId).then((list) => {
      if (!cancelled) useOfflineQueueCountStore.getState().setCount(diagramId, list.length)
    })
    return () => {
      cancelled = true
    }
  }, [diagramId])

  return count
}
