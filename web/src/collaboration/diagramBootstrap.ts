// Resuelve qué proyecto/diagrama usar. Ya NO adivina el proyecto por su cuenta
// (ver ProjectSelector.tsx) -- la elección de proyecto es siempre explícita del
// usuario (o el último que activó, cacheado en localStorage). Lo que sigue
// resolviéndose automáticamente acá es el DIAGRAMA dentro de un proyecto ya
// elegido: se reutiliza el primer diagrama existente (o se crea uno si no hay
// ninguno), necesario para que varios usuarios que comparten un proyecto
// (p.ej. para probar colaboración) terminen viendo y editando el MISMO
// diagrama en vez de que cada uno cree el suyo la primera vez que entra.

import { API_BASE_URL } from '../config'

export const API_BASE = `${API_BASE_URL}/api/v1`

export interface ProjectResponse {
  id: string
  name: string
  description: string | null
  ownerId: string
  createdAt: string | null
}

interface DiagramResponse {
  id: string
  projectId: string
  name: string
  schemaVersion: string
  mutationVersion: number
  updatedAt: string | null
}

export interface SnapshotResponse {
  id: string
  currentState: string
}

export interface ActiveDiagram {
  projectId: string
  diagramId: string
}

export interface ActiveDiagramResult extends ActiveDiagram {
  /** Snapshot ya resuelto durante la búsqueda del diagrama activo (ver
   * `tryResolveCachedActiveDiagram`/`resolveDiagramForProject`). */
  snapshot: SnapshotResponse
}

function storageKey(userId: string): string {
  return `diagram-app:active-diagram:${userId}`
}

function readCached(userId: string): ActiveDiagram | null {
  try {
    const raw = localStorage.getItem(storageKey(userId))
    return raw ? (JSON.parse(raw) as ActiveDiagram) : null
  } catch {
    return null
  }
}

export function writeCached(userId: string, value: ActiveDiagram): void {
  try {
    localStorage.setItem(storageKey(userId), JSON.stringify(value))
  } catch {
    // localStorage no disponible (modo privado, cuota llena, etc.): se
    // volverá a resolver en la próxima carga, sin romper el flujo actual.
  }
}

function clearCached(userId: string): void {
  try {
    localStorage.removeItem(storageKey(userId))
  } catch {
    // noop
  }
}

/** Wrapper público de `clearCached` -- usado por DeleteProjectButton tras un
 * borrado exitoso, para que un próximo login no intente reabrir un proyecto
 * que el usuario mismo acaba de eliminar. */
export function clearCachedActiveProject(userId: string): void {
  clearCached(userId)
}

export async function authFetch(token: string, path: string, init?: RequestInit, signal?: AbortSignal): Promise<Response> {
  return fetch(`${API_BASE}${path}`, {
    ...init,
    signal,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
      ...(init?.headers ?? {}),
    },
  })
}

export async function listProjects(token: string, signal?: AbortSignal): Promise<ProjectResponse[]> {
  const res = await authFetch(token, '/projects', undefined, signal)
  if (!res.ok) throw new Error(`No se pudo listar proyectos (${res.status})`)
  return res.json()
}

export async function createProject(
  token: string,
  name: string,
  description: string | null,
  signal?: AbortSignal,
): Promise<ProjectResponse> {
  const res = await authFetch(token, '/projects', { method: 'POST', body: JSON.stringify({ name, description }) }, signal)
  if (!res.ok) throw new Error(`No se pudo crear el proyecto (${res.status})`)
  return res.json()
}

async function createDiagram(token: string, projectId: string, name: string, signal?: AbortSignal): Promise<DiagramResponse> {
  const res = await authFetch(token, `/projects/${projectId}/diagrams`, { method: 'POST', body: JSON.stringify({ name }) }, signal)
  if (!res.ok) throw new Error(`No se pudo crear el diagrama (${res.status})`)
  return res.json()
}

async function listDiagrams(token: string, projectId: string, signal?: AbortSignal): Promise<DiagramResponse[]> {
  const res = await authFetch(token, `/projects/${projectId}/diagrams`, undefined, signal)
  if (!res.ok) throw new Error(`No se pudo listar diagramas (${res.status})`)
  return res.json()
}

export async function fetchSnapshot(token: string, diagramId: string, signal?: AbortSignal): Promise<SnapshotResponse> {
  const res = await authFetch(token, `/diagrams/${diagramId}/snapshot`, undefined, signal)
  if (!res.ok) throw new Error(`No se pudo obtener el snapshot (${res.status})`)
  return res.json()
}

/**
 * Intenta reutilizar el proyecto/diagrama que este usuario activó explícitamente
 * la última vez en este navegador (ver ProjectSelector.tsx -- es el único lugar
 * que ahora decide QUÉ proyecto abrir). Devuelve `null` si no hay nada cacheado
 * o si el diagrama cacheado ya no existe / el usuario perdió acceso (p.ej. lo
 * sacaron del proyecto, o el proyecto se borró) -- en ese caso limpia el caché y
 * deja que el llamador decida qué mostrar (el selector), en vez de adivinar un
 * proyecto por su cuenta como hacía la vieja `ensureActiveDiagram`.
 */
export async function tryResolveCachedActiveDiagram(
  token: string,
  userId: string,
  signal?: AbortSignal,
): Promise<ActiveDiagramResult | null> {
  const cached = readCached(userId)
  if (!cached) return null

  try {
    const snapshot = await fetchSnapshot(token, cached.diagramId, signal)
    return { ...cached, snapshot }
  } catch (err) {
    if (err instanceof DOMException && err.name === 'AbortError') throw err
    clearCached(userId)
    return null
  }
}

/**
 * Dado un proyecto YA elegido por el usuario (selector, o recién creado),
 * resuelve qué diagrama abrir dentro de él: reutiliza el primero existente (para
 * que varios usuarios que entran al mismo proyecto vean el mismo diagrama) o crea
 * uno si no hay ninguno. Si hay que crear uno, ni siquiera hace falta pedir el
 * snapshot: un diagrama recién creado siempre arranca vacío (ver
 * `DiagramController.createDiagram`), así que se construye el snapshot vacío
 * localmente. Guarda el resultado como el proyecto activo del usuario.
 */
export async function resolveDiagramForProject(
  token: string,
  userId: string,
  projectId: string,
  signal?: AbortSignal,
): Promise<ActiveDiagramResult> {
  const existingDiagrams = await listDiagrams(token, projectId, signal)
  const existingDiagram = existingDiagrams[0]

  if (existingDiagram) {
    const active: ActiveDiagram = { projectId, diagramId: existingDiagram.id }
    writeCached(userId, active)
    const snapshot = await fetchSnapshot(token, existingDiagram.id, signal)
    return { ...active, snapshot }
  }

  const diagram = await createDiagram(token, projectId, 'Diagrama de prueba', signal)
  const active: ActiveDiagram = { projectId, diagramId: diagram.id }
  writeCached(userId, active)

  const emptySnapshot: SnapshotResponse = {
    id: diagram.id,
    currentState: JSON.stringify({
      schemaVersion: diagram.schemaVersion,
      mutationVersion: diagram.mutationVersion,
      packages: [],
      classes: [],
      relationships: [],
    }),
  }

  return { ...active, snapshot: emptySnapshot }
}
