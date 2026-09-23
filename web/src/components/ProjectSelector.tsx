import { useEffect, useState } from 'react'
import { useAuthStore } from '../auth/useAuthStore'
import { API_BASE_URL } from '../config'
import {
  listProjects,
  resolveDiagramForProject,
  type ActiveDiagramResult,
  type ProjectResponse,
} from '../collaboration/diagramBootstrap'
import { CreateProjectModal } from './CreateProjectModal'
import './ProjectSelector.css'

// Gestión de Proyecto — pantalla intermedia entre el login y el lienzo. Cierra
// el hueco diagnosticado: antes `ensureActiveDiagram` elegía un proyecto a
// ciegas (el primero que devolviera /projects, o auto-creaba uno) sin darle al
// usuario la opción real de ver todos los proyectos a los que pertenece --
// incluidos aquellos a los que lo invitaron, que con el flujo viejo eran
// directamente inalcanzables si ya tenía otro proyecto cacheado en su
// localStorage. GET /projects ya filtra por membresía del lado del backend
// (owner + project_members, confirmado) -- acá se muestra tal cual viene, sin
// volver a filtrar nada en el frontend.

interface ProjectSelectorProps {
  onActivated: (result: ActiveDiagramResult) => void
}

type LoadState = 'loading' | 'loaded' | 'error'

export function ProjectSelector({ onActivated }: ProjectSelectorProps) {
  const token = useAuthStore((state) => state.token)
  const userId = useAuthStore((state) => state.userId)
  const logout = useAuthStore((state) => state.logout)

  const [loadState, setLoadState] = useState<LoadState>('loading')
  const [projects, setProjects] = useState<ProjectResponse[]>([])
  const [loadError, setLoadError] = useState<string | null>(null)
  // Se incrementa para forzar un reintento sin duplicar la lógica de carga del
  // efecto (el efecto solo depende de `token`, que no cambia entre reintentos).
  const [reloadToken, setReloadToken] = useState(0)

  const [activatingProjectId, setActivatingProjectId] = useState<string | null>(null)
  const [activateError, setActivateError] = useState<string | null>(null)

  useEffect(() => {
    if (!token) return
    let cancelled = false
    const controller = new AbortController()

    async function load() {
      setLoadState('loading')
      setLoadError(null)
      try {
        const result = await listProjects(token as string, controller.signal)
        if (cancelled) return
        setProjects(result)
        setLoadState('loaded')
      } catch (err) {
        if (cancelled || (err instanceof DOMException && err.name === 'AbortError')) return
        setLoadError(err instanceof Error ? err.message : `No se pudo contactar al backend en ${API_BASE_URL}`)
        setLoadState('error')
      }
    }

    load()
    return () => {
      cancelled = true
      controller.abort()
    }
  }, [token, reloadToken])

  async function handleEnter(projectId: string) {
    if (!token || !userId) return
    setActivatingProjectId(projectId)
    setActivateError(null)
    try {
      const result = await resolveDiagramForProject(token, userId, projectId)
      onActivated(result)
    } catch (err) {
      setActivateError(err instanceof Error ? err.message : 'No se pudo abrir el proyecto')
      setActivatingProjectId(null)
    }
  }

  return (
    <div className="project-selector">
      <div className="project-selector__card">
        <div className="project-selector__header">
          <h1>Tus proyectos</h1>
          <button type="button" className="project-selector__logout" onClick={logout}>
            Salir
          </button>
        </div>

        {activateError && <p className="project-selector__error">{activateError}</p>}

        {loadState === 'loading' && <p className="project-selector__hint">Cargando proyectos…</p>}

        {loadState === 'error' && (
          <div className="project-selector__empty">
            <p className="project-selector__error">{loadError}</p>
            <button type="button" onClick={() => setReloadToken((n) => n + 1)}>
              Reintentar
            </button>
          </div>
        )}

        {loadState === 'loaded' && projects.length === 0 && (
          <div className="project-selector__empty">
            <p className="project-selector__hint">
              Todavía no formás parte de ningún proyecto. Creá el primero para empezar a modelar.
            </p>
            <CreateProjectModal onCreated={(project) => handleEnter(project.id)} />
          </div>
        )}

        {loadState === 'loaded' && projects.length > 0 && (
          <>
            <ul className="project-selector__list">
              {projects.map((project) => {
                const isOwner = project.ownerId === userId
                const isActivating = activatingProjectId === project.id
                return (
                  <li key={project.id} className="project-selector__item">
                    <div className="project-selector__item-info">
                      <span className="project-selector__item-name">{project.name}</span>
                      {project.description && (
                        <span className="project-selector__item-description">{project.description}</span>
                      )}
                    </div>
                    <span className={`project-selector__badge${isOwner ? ' project-selector__badge--owner' : ''}`}>
                      {isOwner ? 'Owner' : 'Miembro'}
                    </span>
                    <button
                      type="button"
                      className="project-selector__enter"
                      onClick={() => handleEnter(project.id)}
                      disabled={activatingProjectId !== null}
                    >
                      {isActivating ? 'Entrando…' : 'Entrar'}
                    </button>
                  </li>
                )
              })}
            </ul>
            <div className="project-selector__footer">
              <CreateProjectModal onCreated={(project) => handleEnter(project.id)} />
            </div>
          </>
        )}
      </div>
    </div>
  )
}
