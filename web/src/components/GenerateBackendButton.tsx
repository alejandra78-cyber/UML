import { useState } from 'react'
import { authFetch } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import { downloadBlob, parseContentDispositionFilename } from './downloadUtils'
import './GenerateBackendButton.css'

// PKG-05/06 Generación de Artefactos — UC13 (Generar Backend Spring Boot).
// Conectado de verdad: el backend de validación + generación ya está cerrado y
// verificado.

interface ValidationIssue {
  severity: 'ERROR' | 'WARNING'
  code: string
  message: string
  relatedId: string | null
}

interface ValidationResponse {
  valid: boolean
  issues: ValidationIssue[]
}

interface GenerateBackendButtonProps {
  diagramId: string | null
}

type Status = 'idle' | 'validating' | 'generating' | 'error'

export function GenerateBackendButton({ diagramId }: GenerateBackendButtonProps) {
  const token = useAuthStore((state) => state.token)
  const [status, setStatus] = useState<Status>('idle')
  const [blockingIssues, setBlockingIssues] = useState<ValidationIssue[] | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function handleClick() {
    if (!diagramId || !token) return
    setBlockingIssues(null)
    setErrorMessage(null)
    setStatus('validating')

    try {
      const validateRes = await authFetch(token, `/diagrams/${diagramId}/validate`)
      if (!validateRes.ok) {
        setErrorMessage(`No se pudo validar el diagrama (${validateRes.status})`)
        setStatus('error')
        return
      }

      const validation: ValidationResponse = await validateRes.json()
      const errors = validation.issues.filter((issue) => issue.severity === 'ERROR')
      if (errors.length > 0) {
        setBlockingIssues(errors)
        setStatus('idle')
        return
      }

      setStatus('generating')
      const genRes = await authFetch(token, `/diagrams/${diagramId}/generate-backend`, { method: 'POST' })
      if (!genRes.ok) {
        setErrorMessage(
          genRes.status === 403
            ? 'Necesitás ser OWNER o EDITOR del proyecto para generar el backend'
            : `No se pudo generar el backend (${genRes.status})`,
        )
        setStatus('error')
        return
      }

      const blob = await genRes.blob()
      const filename = parseContentDispositionFilename(genRes.headers.get('content-disposition')) ?? 'backend-generado.zip'
      downloadBlob(blob, filename)
      setStatus('idle')
    } catch {
      setErrorMessage('No se pudo contactar al backend en http://localhost:8080')
      setStatus('error')
    }
  }

  const isBusy = status === 'validating' || status === 'generating'

  return (
    <span className="generate-backend-btn">
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        title="Validar y generar backend Spring Boot a partir del diagrama"
        onClick={handleClick}
        disabled={!diagramId || isBusy}
      >
        <span aria-hidden>⚙️</span>{' '}
        {status === 'validating' ? 'Validando…' : status === 'generating' ? 'Generando…' : 'Generar backend'}
      </button>

      {blockingIssues && (
        <div className="generate-backend-btn__panel" role="alert">
          <p className="generate-backend-btn__panel-title">
            No se puede generar: corregí estos errores del diagrama primero
          </p>
          <ul>
            {blockingIssues.map((issue, index) => (
              <li key={`${issue.code}-${issue.relatedId ?? index}`}>{issue.message}</li>
            ))}
          </ul>
          <button type="button" onClick={() => setBlockingIssues(null)}>
            Cerrar
          </button>
        </div>
      )}

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
