import { useRef, useState } from 'react'
import { API_BASE } from '../../collaboration/diagramBootstrap'
import { useAuthStore } from '../../auth/useAuthStore'
import { API_BASE_URL } from '../../config'
import { useDiagramStore } from '../../store/useDiagramStore'
import type { Attribute, ClassEntity, Method, Relationship } from '../../types/diagram'
import './VisionModal.css'

// PKG-03 Modelado Asistido por IA — implementa UC10 (Importar Diagrama desde Foto
// de Pizarra)
//
// Conectado de verdad: POST /api/v1/diagrams/{id}/vision-import (multipart,
// backend UC10 cerrado y verificado, 130/130 tests). El proveedor de IA del
// backend migró de Gemini a OpenAI (OpenAiVisionClientImpl, sin cambios en este
// contrato HTTP) -- misma advertencia que VoiceToolbar: sin OPENAI_API_KEY del
// lado de backend, la llamada real al modelo multimodal nunca se probó
// end-to-end con red real, solo con stubs.
//
// Modal Human-in-the-Loop real: la imagen subida a un lado, el borrador detectado
// al otro, con checkboxes para excluir clases/relaciones y edición del nombre de
// cada clase antes de confirmar. Al confirmar, cada clase/relación incluida se
// agrega al diagrama vía addClass/addRelationship -- el mismo camino ya
// verificado que usa el resto de la app -- en vez de la operación BULK_MERGE que
// el plan menciona: su payload exacto no está confirmado del lado de backend
// (applyOperation.ts tampoco la implementa todavía, cae al default no-op), y
// reusar addClass/addRelationship logra el mismo resultado visible, incluida la
// réplica a otros colaboradores vía ADD_CLASS/ADD_RELATIONSHIP (esas sí están
// completamente implementadas), sin depender de un contrato sin verificar.
//
// La forma exacta del "borrador" que devuelve el backend tampoco fue confirmada:
// se asume el mismo esquema canónico usado en el resto de la app
// ({ classes: ClassEntity[], relationships: Relationship[] }), leído de forma
// defensiva por si viene envuelto en una clave "draft"/"model".

interface DraftClassRow extends ClassEntity {
  included: boolean
}

interface DraftRelationshipRow extends Relationship {
  included: boolean
}

type Phase = 'closed' | 'idle' | 'uploading' | 'review' | 'error'

// Grilla propia para las clases confirmadas: no hay garantía de que el borrador
// del backend traiga un layout sin superposición (o traiga alguno en absoluto).
const GRID_COLUMNS = 3
const GRID_SPACING_X = 280
const GRID_SPACING_Y = 220
const GRID_ORIGIN_X = 120
const GRID_ORIGIN_Y = 120

function gridPosition(index: number) {
  return {
    x: GRID_ORIGIN_X + (index % GRID_COLUMNS) * GRID_SPACING_X,
    y: GRID_ORIGIN_Y + Math.floor(index / GRID_COLUMNS) * GRID_SPACING_Y,
  }
}

function extractDraftModel(body: unknown): { classes: ClassEntity[]; relationships: Relationship[] } | null {
  if (!body || typeof body !== 'object') return null
  const record = body as Record<string, unknown>
  const nested = record.draft ?? record.model
  const container = (typeof nested === 'object' && nested !== null ? nested : record) as Record<string, unknown>
  const classes = Array.isArray(container.classes) ? (container.classes as ClassEntity[]) : []
  const relationships = Array.isArray(container.relationships) ? (container.relationships as Relationship[]) : []
  return classes.length > 0 || relationships.length > 0 ? { classes, relationships } : null
}

function formatAttributesSummary(attributes: Attribute[]): string {
  return attributes.map((a) => `${a.name}: ${a.type}`).join(', ')
}

function formatMethodsSummary(methods: Method[]): string {
  return methods.map((m) => `${m.name}()`).join(', ')
}

interface VisionModalProps {
  diagramId: string | null
}

export function VisionModal({ diagramId }: VisionModalProps) {
  const token = useAuthStore((state) => state.token)
  const addClass = useDiagramStore((state) => state.addClass)
  const addRelationship = useDiagramStore((state) => state.addRelationship)

  const [phase, setPhase] = useState<Phase>('closed')
  const [imagePreviewUrl, setImagePreviewUrl] = useState<string | null>(null)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [draftClasses, setDraftClasses] = useState<DraftClassRow[]>([])
  const [draftRelationships, setDraftRelationships] = useState<DraftRelationshipRow[]>([])
  const fileInputRef = useRef<HTMLInputElement>(null)

  function openModal() {
    setPhase('idle')
  }

  function closeModal() {
    if (imagePreviewUrl) URL.revokeObjectURL(imagePreviewUrl)
    setPhase('closed')
    setImagePreviewUrl(null)
    setErrorMessage(null)
    setDraftClasses([])
    setDraftRelationships([])
  }

  function handlePickFile() {
    fileInputRef.current?.click()
  }

  async function handleFileChange(event: React.ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file || !diagramId || !token) return

    if (imagePreviewUrl) URL.revokeObjectURL(imagePreviewUrl)
    setImagePreviewUrl(URL.createObjectURL(file))
    setErrorMessage(null)
    setPhase('uploading')

    try {
      const formData = new FormData()
      // Nombre de campo "image" -- así lo espera el backend (confirmado con log
      // real: antes decía "file" y el backend respondía 400 "Required part
      // 'image' is not present").
      formData.append('image', file)
      // Fetch directo, no authFetch: multipart/form-data necesita que el browser
      // arme el boundary solo -- authFetch fuerza Content-Type: application/json.
      // A propósito NO se fija Content-Type acá: si se pusiera manualmente
      // "multipart/form-data" sin el boundary exacto que genera el propio fetch,
      // el backend no podría parsear las partes -- dejar que fetch lo arme solo
      // al pasarle un FormData como body es lo correcto.
      const res = await fetch(`${API_BASE}/diagrams/${diagramId}/vision-import`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` },
        body: formData,
      })

      if (!res.ok) {
        // 503 = proveedor de IA temporalmente saturado (el backend ya reintentó
        // 2 veces con backoff antes de devolver esto) -- se distingue del resto
        // de los 5xx, que sí son errores genéricos del servidor.
        setErrorMessage(
          res.status === 503
            ? 'El asistente de IA está temporalmente saturado, probá de nuevo en unos minutos'
            : `No se pudo procesar la imagen (${res.status})`,
        )
        setPhase('error')
        return
      }

      const body: unknown = await res.json()
      const draft = extractDraftModel(body)
      if (!draft) {
        setErrorMessage('El backend no detectó ninguna clase ni relación en la imagen')
        setPhase('error')
        return
      }

      setDraftClasses(draft.classes.map((c) => ({ ...c, included: true })))
      setDraftRelationships(draft.relationships.map((r) => ({ ...r, included: true })))
      setPhase('review')
    } catch {
      setErrorMessage(`No se pudo contactar al backend en ${API_BASE_URL}`)
      setPhase('error')
    }
  }

  function toggleClassIncluded(id: string) {
    setDraftClasses((prev) => prev.map((c) => (c.id === id ? { ...c, included: !c.included } : c)))
  }

  function renameDraftClass(id: string, name: string) {
    setDraftClasses((prev) => prev.map((c) => (c.id === id ? { ...c, name } : c)))
  }

  function toggleRelationshipIncluded(id: string) {
    setDraftRelationships((prev) => prev.map((r) => (r.id === id ? { ...r, included: !r.included } : r)))
  }

  function classNameById(id: string): string {
    return draftClasses.find((c) => c.id === id)?.name ?? '?'
  }

  function handleConfirm() {
    const includedClassIds = new Set(draftClasses.filter((c) => c.included).map((c) => c.id))

    draftClasses
      .filter((c) => c.included)
      .forEach((row, index) => {
        addClass({
          id: row.id,
          name: row.name,
          visibility: row.visibility,
          isAbstract: row.isAbstract,
          position: gridPosition(index),
          width: row.width,
          height: row.height,
          attributes: row.attributes,
          methods: row.methods,
        })
      })

    draftRelationships
      .filter((r) => r.included && includedClassIds.has(r.sourceClassId) && includedClassIds.has(r.targetClassId))
      .forEach((row) => {
        addRelationship({
          sourceClassId: row.sourceClassId,
          targetClassId: row.targetClassId,
          type: row.type,
          owningSide: row.owningSide,
          joinTableName: row.joinTableName,
          sourceMultiplicity: row.sourceMultiplicity,
          targetMultiplicity: row.targetMultiplicity,
          sourceRole: row.sourceRole,
          targetRole: row.targetRole,
          isNavigable: row.isNavigable,
          waypoints: [],
        })
      })

    closeModal()
  }

  return (
    <>
      <button type="button" className="vision-modal__trigger" onClick={openModal} disabled={!diagramId}>
        Importar foto
      </button>

      {phase !== 'closed' && (
        <div className="vision-modal__overlay" onClick={closeModal}>
          <div
            className={`vision-modal__dialog${phase === 'review' ? ' vision-modal__dialog--wide' : ''}`}
            onClick={(e) => e.stopPropagation()}
          >
            <input ref={fileInputRef} type="file" accept="image/*" hidden onChange={handleFileChange} />

            {phase === 'idle' && (
              <>
                <p>Subí una foto de una pizarra o un papel con un diagrama dibujado a mano.</p>
                <button type="button" className="vision-modal__close" onClick={handlePickFile}>
                  Elegir imagen
                </button>{' '}
                <button type="button" className="vision-modal__close" onClick={closeModal}>
                  Cancelar
                </button>
              </>
            )}

            {phase === 'uploading' && (
              <div className="vision-modal__review">
                {imagePreviewUrl && <img className="vision-modal__preview" src={imagePreviewUrl} alt="Imagen subida" />}
                <p>Analizando la imagen…</p>
              </div>
            )}

            {phase === 'error' && (
              <>
                <p className="vision-modal__error">{errorMessage}</p>
                <button type="button" className="vision-modal__close" onClick={handlePickFile}>
                  Reintentar con otra imagen
                </button>{' '}
                <button type="button" className="vision-modal__close" onClick={closeModal}>
                  Cerrar
                </button>
              </>
            )}

            {phase === 'review' && (
              <div className="vision-modal__review">
                <div className="vision-modal__preview-pane">
                  {imagePreviewUrl && <img className="vision-modal__preview" src={imagePreviewUrl} alt="Imagen subida" />}
                </div>
                <div className="vision-modal__draft-pane">
                  <p className="vision-modal__draft-title">
                    Revisá lo que se detectó antes de agregarlo al diagrama (podés desmarcar o renombrar):
                  </p>
                  <ul className="vision-modal__draft-list">
                    {draftClasses.map((row) => (
                      <li key={row.id} className="vision-modal__draft-item">
                        <label>
                          <input type="checkbox" checked={row.included} onChange={() => toggleClassIncluded(row.id)} />
                          <input
                            type="text"
                            className="vision-modal__draft-name-input"
                            value={row.name}
                            onChange={(e) => renameDraftClass(row.id, e.target.value)}
                            disabled={!row.included}
                          />
                        </label>
                        {row.attributes.length > 0 && (
                          <div className="vision-modal__draft-detail">Atributos: {formatAttributesSummary(row.attributes)}</div>
                        )}
                        {row.methods.length > 0 && (
                          <div className="vision-modal__draft-detail">Métodos: {formatMethodsSummary(row.methods)}</div>
                        )}
                      </li>
                    ))}
                  </ul>
                  {draftRelationships.length > 0 && (
                    <ul className="vision-modal__draft-list">
                      {draftRelationships.map((row) => (
                        <li key={row.id} className="vision-modal__draft-item">
                          <label>
                            <input
                              type="checkbox"
                              checked={row.included}
                              onChange={() => toggleRelationshipIncluded(row.id)}
                            />
                            {classNameById(row.sourceClassId)} — {row.type} → {classNameById(row.targetClassId)}
                          </label>
                        </li>
                      ))}
                    </ul>
                  )}
                  <div className="vision-modal__actions">
                    <button type="button" className="vision-modal__close" onClick={handleConfirm}>
                      Agregar al diagrama
                    </button>{' '}
                    <button type="button" className="vision-modal__close" onClick={closeModal}>
                      Cancelar
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  )
}
