// Resuelve qué proyecto/diagrama usar (sección 14) sin todavía tener una UI de
// selección de proyectos: reutiliza lo cacheado en localStorage por usuario si
// sigue siendo válido, o si no, reutiliza el primer proyecto accesible (o crea
// uno) y reutiliza el primer diagrama existente de ese proyecto (o crea uno si
// no hay ninguno). Reutilizar el diagrama existente -- no solo el proyecto -- es
// necesario para que varios usuarios que comparten un proyecto (p.ej. para
// probar colaboración) terminen viendo y editando el MISMO diagrama en vez de
// que cada uno cree el suyo la primera vez que entra.

export const API_BASE = 'http://localhost:8080/api/v1'

interface ProjectResponse {
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
  /** Snapshot ya resuelto durante la búsqueda del diagrama activo (ver `ensureActiveDiagram`). */
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

async function listProjects(token: string, signal?: AbortSignal): Promise<ProjectResponse[]> {
  const res = await authFetch(token, '/projects', undefined, signal)
  if (!res.ok) throw new Error(`No se pudo listar proyectos (${res.status})`)
  return res.json()
}

async function createProject(
  token: string,
  name: string,
  description: string,
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
 * Resuelve el proyecto/diagrama activo Y su snapshot en, como máximo, una sola
 * llamada de red a /snapshot (antes se pedía dos veces: una para "validar" el
 * caché y otra en App.tsx para cargar los datos). Si hay que crear un diagrama
 * nuevo, ni siquiera hace falta esa llamada: un diagrama recién creado siempre
 * arranca vacío (ver `DiagramController.createDiagram`), así que se construye
 * el snapshot vacío localmente.
 */
export async function ensureActiveDiagram(token: string, userId: string, signal?: AbortSignal): Promise<ActiveDiagramResult> {
  const cached = readCached(userId)
  if (cached) {
    try {
      const snapshot = await fetchSnapshot(token, cached.diagramId, signal)
      return { ...cached, snapshot }
    } catch (err) {
      if (err instanceof DOMException && err.name === 'AbortError') throw err
      // El diagrama cacheado ya no existe o el usuario perdió acceso: se recrea.
      clearCached(userId)
    }
  }

  const projects = await listProjects(token, signal)
  const project = projects[0] ?? (await createProject(token, 'Proyecto de prueba', 'Creado automáticamente por el diagramador', signal))

  const existingDiagrams = await listDiagrams(token, project.id, signal)
  const existingDiagram = existingDiagrams[0]

  if (existingDiagram) {
    const active: ActiveDiagram = { projectId: project.id, diagramId: existingDiagram.id }
    writeCached(userId, active)
    const snapshot = await fetchSnapshot(token, existingDiagram.id, signal)
    return { ...active, snapshot }
  }

  const diagram = await createDiagram(token, project.id, 'Diagrama de prueba', signal)
  const active: ActiveDiagram = { projectId: project.id, diagramId: diagram.id }
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
