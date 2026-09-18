import { useEffect, useRef, useState } from 'react'
import { authFetch } from '../../collaboration/diagramBootstrap'
import { useAuthStore } from '../../auth/useAuthStore'
import './VoiceToolbar.css'

// PKG-03 Modelado Asistido por IA — implementa UC08 (Modelar por Comando de Voz) y
// UC09 (Modelar por Comando de Texto)
//
// Conectado de verdad: POST /api/v1/diagrams/{id}/ai-command (backend UC08/UC09
// cerrado y verificado, 130/130 tests). El proveedor de IA del backend migró de
// Gemini a OpenAI (OpenAiApiClientImpl, sin cambios en este contrato HTTP) --
// OJO: sin OPENAI_API_KEY configurada del lado de backend no se puede probar
// contra el proveedor real, solo contra stubs sin red; verificarlo es tarea del
// usuario.
//
// El texto tipado y el transcripto por voz van al MISMO endpoint: la voz solo
// resuelve "audio -> texto" vía Web Speech API del navegador
// (SpeechRecognition/webkitSpeechRecognition, ver types/speechRecognition.d.ts) y
// reusa el mismo submit que el campo de texto (RF-02.1: CommandPromptInput sigue
// siendo la alternativa para ambientes con ruido o sin soporte de voz).
//
// Shape confirmado por backend (VoiceCommandResponse, ai/dto/VoiceCommandResponse.java)
// -- ya no se lee de forma defensiva. El cambio real en el diagrama llega por el
// broadcast STOMP normal (cada operación aplicada se difunde igual que una
// mutación manual); este cuerpo HTTP es solo el resumen de confirmación.
interface VoiceCommandResponse {
  guardrailRejected: boolean
  guardrailMessage: string | null
  appliedCount: number
  rejectedCount: number
  truncated: boolean
  rejectedReasons: string[]
}

interface AiCommandFeedback {
  kind: 'success' | 'partial' | 'guardrail' | 'error'
  message: string
}

function buildFeedback(response: VoiceCommandResponse): AiCommandFeedback {
  if (response.guardrailRejected) {
    // El guardrail SIEMPRE viene como 200 (es un resultado de negocio válido, no
    // un error) -- nunca se llamó al proveedor de IA para aplicar nada, no hay
    // broadcast STOMP que esperar en este caso.
    return { kind: 'guardrail', message: response.guardrailMessage ?? 'El asistente no puede aplicar ese pedido.' }
  }

  const parts: string[] = [
    response.appliedCount === 1 ? '1 operación aplicada.' : `${response.appliedCount} operaciones aplicadas.`,
  ]
  if (response.truncated) {
    parts.push('El asistente de IA interpretó más de 5 operaciones; se aplicaron solo las primeras 5 (RF-02.6).')
  }
  if (response.rejectedCount > 0) {
    parts.push(
      `${response.rejectedCount === 1 ? '1 operación rechazada' : `${response.rejectedCount} operaciones rechazadas`}: ${response.rejectedReasons.join('; ')}`,
    )
  }

  return {
    kind: response.rejectedCount > 0 || response.truncated ? 'partial' : 'success',
    message: parts.join(' '),
  }
}

function getSpeechRecognitionCtor(): (new () => SpeechRecognitionLike) | null {
  return window.SpeechRecognition ?? window.webkitSpeechRecognition ?? null
}

interface VoiceToolbarProps {
  diagramId: string | null
}

export function VoiceToolbar({ diagramId }: VoiceToolbarProps) {
  const token = useAuthStore((state) => state.token)
  const [commandText, setCommandText] = useState('')
  const [isListening, setIsListening] = useState(false)
  const [isSending, setIsSending] = useState(false)
  const [feedback, setFeedback] = useState<AiCommandFeedback | null>(null)
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null)

  const speechSupported = getSpeechRecognitionCtor() !== null

  useEffect(() => {
    return () => {
      recognitionRef.current?.stop()
    }
  }, [])

  async function sendCommand(text: string) {
    const trimmed = text.trim()
    if (!trimmed || !diagramId || !token) return

    setFeedback(null)
    setIsSending(true)
    try {
      const res = await authFetch(token, `/diagrams/${diagramId}/ai-command`, {
        method: 'POST',
        body: JSON.stringify({ command: trimmed }),
      })

      if (!res.ok) {
        // 404/403 son los únicos casos de error HTTP real de este endpoint (rol
        // insuficiente o diagrama inexistente, antes de llegar a interpretar nada)
        // -- el guardrail en cambio siempre responde 200, ver buildFeedback. 503 =
        // proveedor de IA temporalmente saturado (el backend ya reintentó 2 veces
        // con backoff antes de devolver esto).
        setFeedback({
          kind: 'error',
          message:
            res.status === 403
              ? 'Necesitás ser miembro con permisos de edición para usar comandos de IA'
              : res.status === 404
                ? 'No se encontró el diagrama'
                : res.status === 503
                  ? 'El asistente de IA está temporalmente saturado, probá de nuevo en unos minutos'
                  : `El backend rechazó el comando (${res.status})`,
        })
        return
      }

      const response: VoiceCommandResponse = await res.json()
      setFeedback(buildFeedback(response))
      setCommandText('')
    } catch {
      setFeedback({ kind: 'error', message: 'No se pudo contactar al backend en http://localhost:8080' })
    } finally {
      setIsSending(false)
    }
  }

  function handleMicClick() {
    if (!speechSupported) return

    if (isListening) {
      recognitionRef.current?.stop()
      return
    }

    const Ctor = getSpeechRecognitionCtor()
    if (!Ctor) return

    const recognition = new Ctor()
    recognition.lang = 'es-ES'
    recognition.interimResults = false
    recognition.continuous = false

    recognition.onresult = (event) => {
      const transcript = event.results[event.results.length - 1]?.[0]?.transcript
      if (transcript) {
        setCommandText(transcript)
        void sendCommand(transcript)
      }
    }
    recognition.onerror = () => setIsListening(false)
    recognition.onend = () => setIsListening(false)

    recognitionRef.current = recognition
    setIsListening(true)
    recognition.start()
  }

  return (
    <div className="voice-toolbar">
      <button
        type="button"
        className={`voice-toolbar__button${isListening ? ' voice-toolbar__button--listening' : ''}`}
        title={
          speechSupported
            ? isListening
              ? 'Escuchando… (clic para detener)'
              : 'Comando de voz'
            : 'Tu navegador no soporta reconocimiento de voz — usá el campo de texto'
        }
        onClick={handleMicClick}
        disabled={!speechSupported || !diagramId || isSending}
      >
        {isListening ? '🔴' : '🎤'}
      </button>
      <input
        type="text"
        className="voice-toolbar__command-input"
        placeholder="Escribí un comando (p. ej. 'agregá una clase Cliente')"
        title="Comando de texto — alternativa a la voz para ambientes con ruido"
        value={commandText}
        onChange={(e) => setCommandText(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') void sendCommand(commandText)
        }}
        disabled={!diagramId || isSending}
      />
      {feedback && (
        <div className={`voice-toolbar__feedback voice-toolbar__feedback--${feedback.kind}`} role="status">
          {feedback.message}
          <button type="button" onClick={() => setFeedback(null)}>
            ×
          </button>
        </div>
      )}
    </div>
  )
}
