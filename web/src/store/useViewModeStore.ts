// Preferencia LOCAL de visualización del cliente (RF-01.6, PLAN_ARQUITECTONICO.md).
// Conmuta cómo se RENDERIZA el mismo CanonicalModel (UML vs. ER); no se sincroniza
// vía STOMP ni se persiste en el backend -- cada usuario puede tener su propio modo.
import { create } from 'zustand'

export type ViewMode = 'UML' | 'ER'

interface ViewModeState {
  viewMode: ViewMode
  toggleViewMode: () => void
  setViewMode: (mode: ViewMode) => void
}

export const useViewModeStore = create<ViewModeState>((set) => ({
  viewMode: 'UML',
  toggleViewMode: () => set((state) => ({ viewMode: state.viewMode === 'UML' ? 'ER' : 'UML' })),
  setViewMode: (mode) => set({ viewMode: mode }),
}))
