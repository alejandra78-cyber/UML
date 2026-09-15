import { useViewModeStore } from '../store/useViewModeStore'
import './ViewModeToggle.css'

/**
 * Selector de barra de herramientas (RF-01.6): conmuta cómo se RENDERIZA el mismo
 * CanonicalModel entre notación UML y notación ER. Es una preferencia LOCAL del
 * cliente -- no toca STOMP ni el backend, solo lee/escribe useViewModeStore.
 */
export function ViewModeToggle() {
  const viewMode = useViewModeStore((state) => state.viewMode)
  const setViewMode = useViewModeStore((state) => state.setViewMode)

  return (
    <div className="view-mode-toggle" role="group" aria-label="Modo de vista del diagrama">
      <button
        type="button"
        className={`view-mode-toggle__option${viewMode === 'UML' ? ' view-mode-toggle__option--active' : ''}`}
        aria-pressed={viewMode === 'UML'}
        onClick={() => setViewMode('UML')}
      >
        UML
      </button>
      <button
        type="button"
        className={`view-mode-toggle__option${viewMode === 'ER' ? ' view-mode-toggle__option--active' : ''}`}
        aria-pressed={viewMode === 'ER'}
        onClick={() => setViewMode('ER')}
      >
        ER
      </button>
    </div>
  )
}
