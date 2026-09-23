import { useState, type FormEvent } from 'react'
import { authFetch } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import { API_BASE_URL } from '../config'
import './ProjectModals.css'

// Gestión de Proyecto — UC18 (Invitar Miembro). Conectado de verdad: el backend
// ya está cerrado. POST /projects/{id}/members devuelve 201 si es alta nueva o
// 200 si actualiza el rol de alguien que ya era miembro -- se distingue para
// mostrar el mensaje de éxito acorde.

type ProjectRole = 'OWNER' | 'EDITOR' | 'VIEWER'

interface InviteMemberModalProps {
  projectId: string | null
}

export function InviteMemberModal({ projectId }: InviteMemberModalProps) {
  const token = useAuthStore((state) => state.token)
  const [open, setOpen] = useState(false)
  const [email, setEmail] = useState('')
  const [role, setRole] = useState<ProjectRole>('EDITOR')
  const [status, setStatus] = useState<'idle' | 'saving' | 'error'>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [successMessage, setSuccessMessage] = useState<string | null>(null)

  function openModal() {
    setOpen(true)
    setEmail('')
    setRole('EDITOR')
    setStatus('idle')
    setErrorMessage(null)
    setSuccessMessage(null)
  }

  function closeModal() {
    setOpen(false)
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (!token || !projectId || !email.trim()) return
    setStatus('saving')
    setErrorMessage(null)
    setSuccessMessage(null)
    try {
      const res = await authFetch(token, `/projects/${projectId}/members`, {
        method: 'POST',
        body: JSON.stringify({ email: email.trim(), role }),
      })

      if (res.status === 201) {
        setSuccessMessage(`${email.trim()} agregado al proyecto como ${role}`)
        setStatus('idle')
        return
      }
      if (res.status === 200) {
        setSuccessMessage(`Rol de ${email.trim()} actualizado a ${role}`)
        setStatus('idle')
        return
      }
      if (res.status === 404) {
        setErrorMessage('No existe un usuario con ese email')
      } else if (res.status === 403) {
        setErrorMessage('Necesitás ser OWNER del proyecto para invitar miembros')
      } else {
        setErrorMessage(`No se pudo invitar al miembro (${res.status})`)
      }
      setStatus('error')
    } catch {
      setErrorMessage(`No se pudo contactar al backend en ${API_BASE_URL}`)
      setStatus('error')
    }
  }

  return (
    <>
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        title="Invitar miembro al proyecto activo"
        onClick={openModal}
        disabled={!projectId}
      >
        <span aria-hidden>👥</span> Invitar
      </button>
      {open && (
        <div className="project-modal__overlay" onClick={closeModal}>
          <div className="project-modal__dialog" onClick={(e) => e.stopPropagation()}>
            <form onSubmit={handleSubmit}>
              <h2>Invitar miembro</h2>
              <label>
                Email
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  disabled={status === 'saving'}
                  autoFocus
                />
              </label>
              <label>
                Rol
                <select value={role} onChange={(e) => setRole(e.target.value as ProjectRole)} disabled={status === 'saving'}>
                  <option value="OWNER">OWNER</option>
                  <option value="EDITOR">EDITOR</option>
                  <option value="VIEWER">VIEWER</option>
                </select>
              </label>
              {errorMessage && <p className="project-modal__error">{errorMessage}</p>}
              {successMessage && <p className="project-modal__success">{successMessage}</p>}
              <div className="project-modal__actions">
                <button type="button" onClick={closeModal} disabled={status === 'saving'}>
                  Cerrar
                </button>
                <button type="submit" disabled={status === 'saving' || !email.trim()}>
                  {status === 'saving' ? 'Invitando…' : 'Invitar'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </>
  )
}
