import { useState } from 'react'
import { authFetch } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import { API_BASE_URL } from '../config'
import { downloadBlob, parseContentDispositionFilename } from './downloadUtils'

// PKG-05/06 Generación de Artefactos — UC15 (Exportar a XMI). Conectado de
// verdad: el backend ya está cerrado y verificado.

interface ExportXmiButtonProps {
  diagramId: string | null
}

export function ExportXmiButton({ diagramId }: ExportXmiButtonProps) {
  const token = useAuthStore((state) => state.token)
  const [busy, setBusy] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function handleClick() {
    if (!diagramId || !token) return
    setErrorMessage(null)
    setBusy(true)
    try {
      const res = await authFetch(token, `/diagrams/${diagramId}/export-xmi`)
      if (!res.ok) {
        setErrorMessage(`No se pudo exportar a XMI (${res.status})`)
        return
      }

      // No asumimos application/xml ni text/xml a ciegas: se respeta el
      // Content-Type real que haya elegido el backend para la respuesta.
      const contentType = res.headers.get('content-type') ?? 'application/xml'
      const blob = new Blob([await res.arrayBuffer()], { type: contentType })
      const filename = parseContentDispositionFilename(res.headers.get('content-disposition')) ?? 'diagrama.xmi'
      downloadBlob(blob, filename)
    } catch {
      setErrorMessage(`No se pudo contactar al backend en ${API_BASE_URL}`)
    } finally {
      setBusy(false)
    }
  }

  return (
    <span className="diagram-toolbar__package-btn">
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        title="Exportar diagrama a XMI"
        onClick={handleClick}
        disabled={!diagramId || busy}
      >
        <span aria-hidden>📤</span> {busy ? 'Exportando…' : 'Exportar XMI'}
      </button>
      {errorMessage && <span className="diagram-toolbar__tooltip">{errorMessage}</span>}
    </span>
  )
}
