import { useState } from 'react'
import { authFetch } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import { downloadBlob, parseContentDispositionFilename } from './downloadUtils'
import './GenerateBackendButton.css'

// PKG-05/06 Generación de Artefactos — UC14 (Generar Aplicación Móvil).
// Conectado de verdad: el backend (POST /api/v1/diagrams/{id}/generate-mobile,
// motor FreeMarker reutilizado del generador de backend) ya está cerrado y
// verificado. Mismo patrón que GenerateBackendButton (sin el paso de validación
// previa, que backend no pidió para este caso de uso).

interface GenerateMobileAppButtonProps {
  diagramId: string | null
}

type Status = 'idle' | 'generating' | 'error'

export function GenerateMobileAppButton({ diagramId }: GenerateMobileAppButtonProps) {
  const token = useAuthStore((state) => state.token)
  const [status, setStatus] = useState<Status>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function handleClick() {
    if (!diagramId || !token) return
    setErrorMessage(null)
    setStatus('generating')

    try {
      const res = await authFetch(token, `/diagrams/${diagramId}/generate-mobile`, { method: 'POST' })
      if (!res.ok) {
        setErrorMessage(
          res.status === 403
            ? 'Necesitás ser OWNER o EDITOR del proyecto para generar la app móvil'
            : `No se pudo generar la app móvil (${res.status})`,
        )
        setStatus('error')
        return
      }

      const blob = await res.blob()
      const filename = parseContentDispositionFilename(res.headers.get('content-disposition')) ?? 'app-movil-generada.zip'
      downloadBlob(blob, filename)
      setStatus('idle')
    } catch {
      setErrorMessage('No se pudo contactar al backend en http://localhost:8080')
      setStatus('error')
    }
  }

  return (
    <span className="generate-backend-btn">
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        title="Generar aplicación móvil con asistente de voz a partir del diagrama"
        onClick={handleClick}
        disabled={!diagramId || status === 'generating'}
      >
        <span aria-hidden>📱</span> {status === 'generating' ? 'Generando…' : 'App móvil'}
      </button>

      {errorMessage && (
        <div className="generate-backend-btn__panel generate-backend-btn__panel--error" role="alert">
          <p>{errorMessage}</p>
          <button type="button" onClick={() => setErrorMessage(null)}>
            Cerrar
          </button>
        </div>
      )}
    </span>
  )
}
