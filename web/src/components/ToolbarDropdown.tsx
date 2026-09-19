import { useEffect, useLayoutEffect, useRef, useState, type ReactNode } from 'react'
import './ToolbarDropdown.css'

// Margen mínimo respetado contra cada borde del viewport al reposicionar el
// panel (ver el efecto de reposicionamiento más abajo).
const VIEWPORT_MARGIN_PX = 8

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
  const panelRef = useRef<HTMLDivElement>(null)
  // `null` = todavía sin medir (primer render tras abrir): se apoya en el
  // `right: 0` por defecto de ToolbarDropdown.css hasta que el efecto de abajo
  // mide y corrige, así nunca hay un frame con el panel sin posicionar.
  const [panelLeft, setPanelLeft] = useState<number | null>(null)

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

  // BUG reportado con captura real: en ventana angosta, `flex-wrap` en la barra
  // superior puede envolver este botón cerca del borde IZQUIERDO del viewport
  // (no siempre cerca del derecho, que es lo único que el `right: 0` fijo de la
  // hoja de estilos asumía). Un panel que crece hacia la izquierda desde un
  // botón ya pegado a la izquierda termina con coordenadas negativas --
  // literalmente fuera de la ventana (confirmado con Playwright: x: -97px en
  // un viewport de 700px, cortando "Generar backend" a solo "ackend" visible).
  // Se mide la posición real del botón/panel ya renderizado y se calcula un
  // `left` (en vez de depender de `right`) clampeado para que el panel entero
  // quede siempre dentro del viewport, con un margen mínimo a cada lado.
  useLayoutEffect(() => {
    if (!open) {
      setPanelLeft(null)
      return
    }

    function reposition() {
      if (!containerRef.current || !panelRef.current) return
      const containerRect = containerRef.current.getBoundingClientRect()
      const panelWidth = panelRef.current.offsetWidth
      // Anclaje "natural" -- equivalente a right:0: el borde derecho del panel
      // coincide con el borde derecho del botón.
      const naturalLeft = containerRect.width - panelWidth
      const idealLeftInViewport = containerRect.left + naturalLeft
      const minLeftInViewport = VIEWPORT_MARGIN_PX
      const maxLeftInViewport = window.innerWidth - panelWidth - VIEWPORT_MARGIN_PX
      const clampedLeftInViewport = Math.min(Math.max(idealLeftInViewport, minLeftInViewport), maxLeftInViewport)
      // Vuelve a coordenadas LOCALES (relativas al contenedor position:relative,
      // que es lo que espera el `left` inline del panel position:absolute).
      setPanelLeft(clampedLeftInViewport - containerRect.left)
    }

    reposition()
    window.addEventListener('resize', reposition)
    return () => window.removeEventListener('resize', reposition)
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
        <div
          ref={panelRef}
          className="toolbar-dropdown__panel"
          role="menu"
          style={panelLeft !== null ? { left: panelLeft, right: 'auto' } : undefined}
        >
          {children}
        </div>
      )}
    </div>
  )
}
