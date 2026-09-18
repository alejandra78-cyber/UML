import { createPortal } from 'react-dom'
import type { SyncReport } from '../../offline/syncOffline'
import './ReconciliationModal.css'

interface ReconciliationModalProps {
  report: SyncReport
  onClose: () => void
}

// PKG-04 Resiliencia Offline -- implementa UC12 (Sincronizar Cambios al Reconectar).
/**
 * Reporte de sincronización que se muestra al reconectar con cambios que
 * quedaron encolados offline (ver features/offline/useOfflineSync.ts).
 * `syncOfflineQueue` (offline/syncOffline.ts) llama de verdad a
 * POST /api/v1/diagrams/{id}/sync-offline (UC12, backend cerrado y verificado);
 * `discardedDetails` refleja los descartes reales que compute el rebase del
 * backend, no un mock.
 *
 * Portal a document.body + overlay, mismo patrón que ClassContextMenu.tsx.
 */
export function ReconciliationModal({ report, onClose }: ReconciliationModalProps) {
  const hasDiscarded = report.discardedCount > 0

  return createPortal(
    <div className="reconciliation-modal__overlay" onClick={onClose}>
      <div
        className="reconciliation-modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="reconciliation-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <h2 id="reconciliation-modal-title">Sincronización completada</h2>
        <p className="reconciliation-modal__summary">
          {report.appliedCount} {report.appliedCount === 1 ? 'operación aplicada' : 'operaciones aplicadas'}
        </p>

        {hasDiscarded && (
          <div className="reconciliation-modal__warning" role="alert">
            <p>
              {report.discardedCount}{' '}
              {report.discardedCount === 1 ? 'cambio descartado' : 'cambios descartados'} por conflicto con
              ediciones de otros colaboradores:
            </p>
            <ul>
              {report.discardedDetails.map((detail, index) => (
                <li key={index}>{detail}</li>
              ))}
            </ul>
          </div>
        )}

        <button type="button" className="reconciliation-modal__close" onClick={onClose}>
          Cerrar
        </button>
      </div>
    </div>,
    document.body,
  )
}
