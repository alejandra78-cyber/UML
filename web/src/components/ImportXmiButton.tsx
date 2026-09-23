import { useRef, useState, type ChangeEvent } from 'react'
import { API_BASE, writeCached } from '../collaboration/diagramBootstrap'
import { useAuthStore } from '../auth/useAuthStore'
import { API_BASE_URL } from '../config'
import './GenerateBackendButton.css'

// PKG-05/06 Generación de Artefactos — UC16 (Importar Diagrama desde XMI).
// Conectado de verdad: el backend (POST /api/v1/projects/{projectId}/import-xmi)
// ya está cerrado y verificado.
//
// IMPORTANTE (UX): este endpoint es a nivel de PROYECTO, no de diagrama -- crea
// un DIAGRAMA NUEVO dentro del proyecto (201 + DiagramResponse), NO fusiona el
// XMI sobre el diagrama que el usuario tiene abierto en el lienzo. La UI tiene
// que dejarlo explícito: nunca asumas que el canvas actual va a cambiar solo.
//
// IMPORTANTE (contrato, confirmado con backend): el endpoint espera
// `@RequestBody String xmiContent` -- el XML crudo va DIRECTO como body, NO
// multipart/form-data (decisión explícita del backend, simétrica con cómo el
// mismo endpoint exporta bytes de XML crudo). `Content-Type: application/xml`
// recomendado (Spring no lo exige para bindear a String, pero es explícito).
// El nombre del diagrama es un query param OPCIONAL (`diagramName`): si se
// omite, el backend lo resuelve del `<uml:Model>` del propio XMI, o cae a
// "Diagrama importado".

interface ImportXmiButtonProps {
  projectId: string | null
}

interface DiagramResponse {
  id: string
  projectId: string
  name: string
  schemaVersion: string
  mutationVersion: number
  updatedAt: string | null
}

type Status = 'idle' | 'importing' | 'error'

export function ImportXmiButton({ projectId }: ImportXmiButtonProps) {
  const token = useAuthStore((state) => state.token)
  const userId = useAuthStore((state) => state.userId)
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [status, setStatus] = useState<Status>('idle')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [createdDiagram, setCreatedDiagram] = useState<DiagramResponse | null>(null)
  const [diagramName, setDiagramName] = useState('')

  function handleButtonClick() {
    fileInputRef.current?.click()
  }

  async function handleFileChange(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = '' // permite volver a elegir el mismo archivo si hace falta reintentar
    if (!file || !projectId || !token) return

    setErrorMessage(null)
    setCreatedDiagram(null)
    setStatus('importing')

    try {
      const xmlContent = await file.text()
      const trimmedName = diagramName.trim()
      const query = trimmedName ? `?diagramName=${encodeURIComponent(trimmedName)}` : ''
      // Fetch directo (no authFetch): authFetch fuerza Content-Type: application/json,
      // acá el body es el XML crudo, no JSON.
      const res = await fetch(`${API_BASE}/projects/${projectId}/import-xmi${query}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/xml',
          Authorization: `Bearer ${token}`,
        },
        body: xmlContent,
      })

      if (!res.ok) {
        let message = `No se pudo importar el XMI (${res.status})`
        if (res.status === 403) {
          message = 'Necesitás ser OWNER o EDITOR del proyecto para importar un XMI'
        } else if (res.status === 400) {
          // El backend puede mandar el detalle del XMI inválido en el cuerpo de
          // la respuesta -- se muestra tal cual si viene, en vez de un genérico.
          const detail = await res.text().catch(() => '')
          message = detail ? `XMI inválido: ${detail}` : 'El archivo XMI no es válido'
        }
        setErrorMessage(message)
        setStatus('error')
        return
      }

      const diagram: DiagramResponse = await res.json()
      setCreatedDiagram(diagram)
      setStatus('idle')
    } catch {
      setErrorMessage(`No se pudo contactar al backend en ${API_BASE_URL}`)
      setStatus('error')
    }
  }

  function openImportedDiagram() {
    if (!createdDiagram || !userId) return
    // Todavía no existe un selector de proyectos/diagramas en la app (fuera de
    // alcance, ver CreateProjectModal). "Abrir" el recién creado reutiliza el
    // mismo mecanismo de caché que ensureActiveDiagram (diagramBootstrap.ts) ya
    // usa para decidir qué diagrama mostrar al entrar: sobreescribirlo y
    // recargar hace que la próxima carga lo resuelva como el diagrama activo.
    writeCached(userId, { projectId: createdDiagram.projectId, diagramId: createdDiagram.id })
    window.location.reload()
  }

  return (
    <span className="generate-backend-btn">
      <input ref={fileInputRef} type="file" accept=".xmi,.xml" hidden onChange={handleFileChange} />
      <input
        type="text"
        className="import-xmi-btn__name-input"
        placeholder="Nombre del diagrama (opcional)"
        title="Si lo dejás vacío, el backend usa el nombre del propio XMI o 'Diagrama importado'"
        value={diagramName}
        onChange={(e) => setDiagramName(e.target.value)}
        disabled={status === 'importing'}
      />
      <button
        type="button"
        className="diagram-toolbar__icon-btn"
        title="Importar diagrama desde un archivo XMI (crea un diagrama nuevo en el proyecto)"
        onClick={handleButtonClick}
        disabled={!projectId || status === 'importing'}
      >
        <span aria-hidden>📥</span> {status === 'importing' ? 'Importando…' : 'Importar XMI'}
      </button>

      {createdDiagram && (
        <div className="generate-backend-btn__panel" role="status">
          <p className="generate-backend-btn__panel-title generate-backend-btn__panel-title--success">
            Se creó un nuevo diagrama a partir de tu archivo XMI: "{createdDiagram.name}"
          </p>
          <p>No reemplaza el diagrama que tenés abierto ahora — es un diagrama nuevo dentro del proyecto.</p>
          <button type="button" onClick={openImportedDiagram}>
            Abrir este diagrama
          </button>{' '}
          <button type="button" onClick={() => setCreatedDiagram(null)}>
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
