import { useState } from 'react'
import './VisionModal.css'

// Placeholder de Fase 4 (RF-03: Modelado por Visión Multimodal). Sin procesamiento
// de imagen real todavía -- solo el botón de entrada y un modal vacío.
export function VisionModal() {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button type="button" className="vision-modal__trigger" onClick={() => setOpen(true)}>
        Importar foto
      </button>
      {open && (
        <div className="vision-modal__overlay" onClick={() => setOpen(false)}>
          <div className="vision-modal__dialog" onClick={(e) => e.stopPropagation()}>
            <p>Próximamente</p>
            <button type="button" className="vision-modal__close" onClick={() => setOpen(false)}>
              Cerrar
            </button>
          </div>
        </div>
      )}
    </>
  )
}
