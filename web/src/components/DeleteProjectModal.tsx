import './ProjectModals.css'

// Gestión de Proyecto — UC19 (Eliminar Proyecto). Reemplaza el window.confirm()
// nativo que tenía DeleteProjectButton.tsx: mismo mensaje de advertencia, misma
// estructura visual que InviteMemberModal.tsx (overlay + project-modal__dialog),
// para que el modal de confirmación de borrado no sea un patrón nuevo suelto
// sino el mismo look de los otros modales de gestión de proyecto. Puramente
// presentacional -- toda la lógica de borrado (DELETE /projects/{id},
// clearCachedActiveProject, onDeleted) sigue viviendo en DeleteProjectButton.tsx.

interface DeleteProjectModalProps {
  open: boolean
  busy: boolean
  errorMessage: string | null
  onCancel: () => void
  onConfirm: () => void
}

export function DeleteProjectModal({ open, busy, errorMessage, onCancel, onConfirm }: DeleteProjectModalProps) {
  if (!open) return null

  return (
    <div className="project-modal__overlay" onClick={onCancel}>
      <div className="project-modal__dialog" onClick={(e) => e.stopPropagation()}>
        <h2>Eliminar proyecto</h2>
        <p>
          Esto elimina el proyecto de forma DEFINITIVA e IRREVERSIBLE, junto con todos sus miembros, diagramas y el
          historial de operaciones asociado. ¿Confirmás que querés eliminarlo?
        </p>
        {errorMessage && <p className="project-modal__error">{errorMessage}</p>}
        <div className="project-modal__actions">
          <button type="button" onClick={onCancel} disabled={busy}>
            Cancelar
          </button>
          <button type="button" className="project-modal__button--danger" onClick={onConfirm} disabled={busy}>
            {busy ? 'Eliminando…' : 'Eliminar'}
          </button>
        </div>
      </div>
    </div>
  )
}
