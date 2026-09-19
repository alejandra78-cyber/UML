import { useState } from 'react'
import { authFetch, clearCachedActiveProject } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'

// Gestión de Proyecto — UC19 (Eliminar Proyecto). Conectado de verdad: el
// backend ya está cerrado. Borra en cascada del lado del servidor (proyecto,
// miembros, diagramas, historial de operaciones), así que se confirma antes con
// window.confirm dejando explícito que es irreversible y qué se pierde -- no
// hace falta un modal de confirmación custom para esto.
//
// Caso borde conocido y aceptado por ahora (no lo resuelve este componente): el
// cascade delete del backend no notifica a conexiones STOMP de OTROS usuarios
// que puedan seguir con el proyecto abierto en su propia pestaña -- solo la
// sesión que ejecuta el borrado se entera y redirige, vía `onDeleted`.
//
// Después de un borrado exitoso ya NO alcanza con un window.alert: el proyecto
// activo cacheado en localStorage (ver diagramBootstrap.ts) apuntaría a un
// proyecto/diagrama que el backend acaba de destruir, así que se limpia acá
// mismo, y `onDeleted` es responsabilidad del padre (ProjectGate) para volver
// al selector de proyectos.

interface DeleteProjectButtonProps {
  projectId: string | null
  onDeleted: () => void
}

export function DeleteProjectButton({ projectId, onDeleted }: DeleteProjectButtonProps) {
  const token = useAuthStore((state) => state.token)
  const userId = useAuthStore((state) => state.userId)
  const [busy, setBusy] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function handleClick() {
    if (!token || !userId || !projectId) return
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
      clearCachedActiveProject(userId)
      onDeleted()
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
        className="diagram-toolbar__icon-btn diagram-toolbar__icon-btn--danger"
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
