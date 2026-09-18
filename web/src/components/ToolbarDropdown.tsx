import { useEffect, useRef, useState, type ReactNode } from 'react'
import './ToolbarDropdown.css'

// Agrupa controles de uso OCASIONAL detrás de un menú desplegable, para que la
// barra superior no siga creciendo linealmente con cada caso de uso nuevo que se
// conecta -- antes tenía 17 controles sueltos en una sola fila sin wrap, y ya se
// cortaba en el borde derecho (ver App.tsx para el detalle de qué quedó agrupado
// acá vs. qué se dejó siempre visible por ser de uso frecuente).
//
// No cierra el panel al hacer clic DENTRO de él a propósito: varios de los
// componentes que se agrupan acá (CreateProjectModal, GenerateBackendButton,
// etc.) son ellos mismos botón+modal con estado propio -- si el panel se
// desmontara en el mismo clic que abre su modal interno, ese estado se perdería
// antes de que el modal llegara a mostrarse. Cierra solo con clic afuera o con
// un segundo clic en el propio disparador.
interface ToolbarDropdownProps {
  icon: string
  label: string
  ariaLabel: string
  children: ReactNode
}

export function ToolbarDropdown({ icon, label, ariaLabel, children }: ToolbarDropdownProps) {
  const [open, setOpen] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [open])

  return (
    <div className="toolbar-dropdown" ref={containerRef}>
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        aria-haspopup="true"
        aria-expanded={open}
        aria-label={ariaLabel}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span aria-hidden>{icon}</span> {label}
        <span className="toolbar-dropdown__caret" aria-hidden>
          ▾
        </span>
      </button>
      {open && (
        <div className="toolbar-dropdown__panel" role="menu">
          {children}
        </div>
      )}
    </div>
  )
}
