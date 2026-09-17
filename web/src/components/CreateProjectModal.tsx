import { useState, type FormEvent } from 'react'
import { authFetch } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import './ProjectModals.css'

// Gestión de Proyecto — UC17 (Crear Proyecto). El POST /api/v1/projects ya
// existe desde antes (lo usa diagramBootstrap.ensureActiveDiagram para la
// auto-creación silenciosa del proyecto inicial); esta pieza es solo la
// capacidad EXPLÍCITA de crear un proyecto adicional vía UI. Deliberadamente no
// "activa" el proyecto creado como el diagrama actual ni reemplaza el flujo de
// ensureActiveDiagram -- eso es un alcance más grande, fuera de esta tarea.

interface ProjectResponse {
  id: string
  name: string
  description: string | null
  ownerId: string
  createdAt: string | null
}

export function CreateProjectModal() {
  const token = useAuthStore((state) => state.token)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [status, setStatus] = useState<'idle' | 'saving' | 'error'>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [created, setCreated] = useState<ProjectResponse | null>(null)

  function openModal() {
    setOpen(true)
    setName('')
    setDescription('')
    setStatus('idle')
    setErrorMessage(null)
    setCreated(null)
  }

  function closeModal() {
    setOpen(false)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!token || !name.trim()) return
    setStatus('saving')
    setErrorMessage(null)
    try {
      const res = await authFetch(token, '/projects', {
        method: 'POST',
        body: JSON.stringify({ name: name.trim(), description: description.trim() || null }),
      })
      if (!res.ok) {
        setErrorMessage(`No se pudo crear el proyecto (${res.status})`)
        setStatus('error')
        return
      }
      const project: ProjectResponse = await res.json()
      setCreated(project)
      setStatus('idle')
    } catch {
      setErrorMessage('No se pudo contactar al backend en http://localhost:8080')
      setStatus('error')
    }
  }

  return (
    <>
      <button type="button" className="diagram-toolbar__icon-btn" title="Crear un nuevo proyecto" onClick={openModal}>
        <span aria-hidden>➕</span> Nuevo proyecto
      </button>
      {open && (
        <div className="project-modal__overlay" onClick={closeModal}>
          <div className="project-modal__dialog" onClick={(e) => e.stopPropagation()}>
            {created ? (
              <>
                <p className="project-modal__success">
                  Proyecto "{created.name}" creado (id: {created.id})
                </p>
                <div className="project-modal__actions">
                  <button type="button" onClick={closeModal}>
                    Cerrar
                  </button>
                </div>
              </>
            ) : (
              <form onSubmit={handleSubmit}>
                <h2>Nuevo proyecto</h2>
                <label>
                  Nombre
                  <input value={name} onChange={(e) => setName(e.target.value)} required disabled={status === 'saving'} autoFocus />
                </label>
                <label>
                  Descripción
                  <textarea value={description} onChange={(e) => setDescription(e.target.value)} disabled={status === 'saving'} />
                </label>
                {errorMessage && <p className="project-modal__error">{errorMessage}</p>}
                <div className="project-modal__actions">
                  <button type="button" onClick={closeModal} disabled={status === 'saving'}>
                    Cancelar
                  </button>
                  <button type="submit" disabled={status === 'saving' || !name.trim()}>
                    {status === 'saving' ? 'Creando…' : 'Crear'}
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}
    </>
  )
}
