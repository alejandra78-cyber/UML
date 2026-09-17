import { useState } from 'react'
import { authFetch } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'

// Gestión de Proyecto — UC19 (Eliminar Proyecto). Conectado de verdad: el
// backend ya está cerrado. Borra en cascada del lado del servidor (proyecto,
// miembros, diagramas, historial de operaciones), así que se confirma antes con
// window.confirm dejando explícito que es irreversible y qué se pierde -- no
// hace falta un modal de confirmación custom para esto.

interface DeleteProjectButtonProps {
  projectId: string | null
}

export function DeleteProjectButton({ projectId }: DeleteProjectButtonProps) {
  const token = useAuthStore((state) => state.token)
  const [busy, setBusy] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function handleClick() {
    if (!token || !projectId) return
    const confirmed = window.confirm(
      'Esto elimina el proyecto de forma DEFINITIVA e IRREVERSIBLE, junto con todos sus miembros, ' +
        'diagramas y el historial de operaciones asociado. ¿Confirmás que querés eliminarlo?',
    )
    if (!confirmed) return

    setBusy(true)
    setErrorMessage(null)
    try {
      const res = await authFetch(token, `/projects/${projectId}`, { method: 'DELETE' })
      if (!res.ok) {
        setErrorMessage(
          res.status === 403 ? 'Necesitás ser OWNER del proyecto para eliminarlo' : `No se pudo eliminar el proyecto (${res.status})`,
        )
        return
      }
      window.alert('Proyecto eliminado.')
    } catch {
      setErrorMessage('No se pudo contactar al backend en http://localhost:8080')
    } finally {
      setBusy(false)
    }
  }

  return (
    <span className="diagram-toolbar__package-btn">
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        title="Eliminar el proyecto activo (irreversible)"
        onClick={handleClick}
        disabled={!projectId || busy}
      >
        <span aria-hidden>🗑️</span> {busy ? 'Eliminando…' : 'Eliminar proyecto'}
      </button>
      {errorMessage && <span className="diagram-toolbar__tooltip">{errorMessage}</span>}
    </span>
  )
}
