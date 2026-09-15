import { createPortal } from 'react-dom'
import './ClassContextMenu.css'

interface ClassContextMenuProps {
  x: number
  y: number
  onAddAttribute: () => void
  onAddMethod: () => void
  onClose: () => void
}

/**
 * Menú contextual estilo Sparx Enterprise Architect (punto 2): clic derecho sobre
 * una clase abre "Agregar > Atributo" / "Agregar > Método". Solo estas dos
 * opciones -- el resto del menú de EA (Port, Reception, etc.) no aplica al modelo
 * canónico de este proyecto.
 *
 * Se monta vía portal a document.body: la clase vive dentro de un nodo de React
 * Flow con su propio transform de pan/zoom, así que un menú `position:absolute`
 * anidado ahí adentro heredaría ese zoom/escala. El portal lo saca de ese árbol
 * para poder usar coordenadas de pantalla (clientX/clientY) directamente.
 */
export function ClassContextMenu({ x, y, onAddAttribute, onAddMethod, onClose }: ClassContextMenuProps) {
  return createPortal(
    <>
      {/* Backdrop invisible de pantalla completa: cualquier clic (o un segundo clic
          derecho) fuera del menú lo cierra, patrón estándar de menú contextual. */}
      <div
        className="class-context-menu__backdrop"
        onClick={onClose}
        onContextMenu={(e) => {
          e.preventDefault()
          onClose()
        }}
      />
      <div className="class-context-menu" style={{ left: x, top: y }} role="menu">
        <div className="class-context-menu__item class-context-menu__item--parent" role="menuitem">
          <span>Agregar</span>
          <span className="class-context-menu__arrow" aria-hidden>
            ▸
          </span>
          <div className="class-context-menu__submenu" role="menu">
            <button
              type="button"
              className="class-context-menu__item class-context-menu__item--action"
              role="menuitem"
              onClick={() => {
                onAddAttribute()
                onClose()
              }}
            >
              Atributo
            </button>
            <button
              type="button"
              className="class-context-menu__item class-context-menu__item--action"
              role="menuitem"
              onClick={() => {
                onAddMethod()
                onClose()
              }}
            >
              Método
            </button>
          </div>
        </div>
      </div>
    </>,
    document.body,
  )
}
