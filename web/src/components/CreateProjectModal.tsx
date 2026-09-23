import { useState, type FormEvent } from 'react'
import { createProject, type ProjectResponse } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import { API_BASE_URL } from '../config'
import './ProjectModals.css'

// Gestión de Proyecto — UC17 (Crear Proyecto). El POST /api/v1/projects ya
// existe desde antes (lo usa diagramBootstrap para la resolución de proyecto
// activo). Antes esta pieza deliberadamente NO activaba el proyecto creado --
// ahora sí: si el llamador pasa `onCreated`, se lo avisa apenas el POST
// resuelve y cierra el modal de inmediato, delegando en el padre (ProjectGate)
// tanto la resolución del diagrama dentro del proyecto nuevo como el propio
// indicador de carga mientras tanto -- mostrar un "creado" acá Y un loading
// allá sería redundante.

interface CreateProjectModalProps {
  onCreated?: (project: ProjectResponse) => void
}

export function CreateProjectModal({ onCreated }: CreateProjectModalProps) {
  const token = useAuthStore((state) => state.token)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [status, setStatus] = useState<'idle' | 'saving' | 'error'>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  function openModal() {
    setOpen(true)
    setName('')
    setDescription('')
    setStatus('idle')
    setErrorMessage(null)
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
      const project = await createProject(token, name.trim(), description.trim() || null)
      setStatus('idle')
      setOpen(false)
      onCreated?.(project)
    } catch (err) {
      // `createProject` (diagramBootstrap.ts) ya arma un mensaje con el status
      // HTTP cuando el backend respondió con un error; cualquier otra excepción
      // (fetch ni siquiera llegó a responder) es una falla de red real.
      setErrorMessage(
        err instanceof Error && err.message.startsWith('No se pudo crear el proyecto')
          ? err.message
          : `No se pudo contactar al backend en ${API_BASE_URL}`,
      )
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
          </div>
        </div>
      )}
    </>
  )
}
