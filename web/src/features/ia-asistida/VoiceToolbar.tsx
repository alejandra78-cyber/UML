import { useEffect, useRef, useState } from 'react'
import { authFetch } from '../../collaboration/diagramBootstrap'
import { useAuthStore } from '../../auth/useAuthStore'
import './VoiceToolbar.css'

// PKG-03 Modelado Asistido por IA — implementa UC08 (Modelar por Comando de Voz) y
// UC09 (Modelar por Comando de Texto)
//
// Conectado de verdad: POST /api/v1/diagrams/{id}/ai-command (backend UC08/UC09
// cerrado y verificado, 130/130 tests). OJO: backend fue explícito en que ninguna
// llamada real a Gemini fue probada end-to-end de su lado (sin GEMINI_API_KEY en
// ese entorno, solo stubs sin red) -- de este lado tampoco se puede probar contra
// Gemini real sin esa key configurada; verificarlo es tarea del usuario.
//
// El texto tipado y el transcripto por voz van al MISMO endpoint: la voz solo
// resuelve "audio -> texto" vía Web Speech API del navegador
// (SpeechRecognition/webkitSpeechRecognition, ver types/speechRecognition.d.ts) y
// reusa el mismo submit que el campo de texto (RF-02.1: CommandPromptInput sigue
// siendo la alternativa para ambientes con ruido o sin soporte de voz).
//
// La forma exacta del cuerpo de respuesta (cómo distinguir el guardrail de un
// éxito) no fue confirmada por backend -- se lee de forma defensiva (varios
// nombres de campo posibles) en vez de asumir un shape estricto.

interface AiCommandFeedback {
  kind: 'success' | 'info' | 'error'
  message: string
}

function extractMessage(body: unknown): string | null {
  if (body && typeof body === 'object') {
    const record = body as Record<string, unknown>
    for (const key of ['message', 'guardrailMessage', 'error', 'reason']) {
      const value = record[key]
      if (typeof value === 'string' && value.trim()) return value
    }
  }
  return null
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

      let body: unknown = null
      try {
        body = await res.json()
      } catch {
        // Respuesta sin cuerpo JSON (p.ej. 204): no es un error por sí solo.
      }
      const message = extractMessage(body)

      if (!res.ok) {
        setFeedback({ kind: 'error', message: message ?? `El backend rechazó el comando (${res.status})` })
      } else if (message) {
        // No hay forma confirmada de distinguir "guardrail" de "éxito con nota" en
        // el shape de la respuesta -- si vino un mensaje explícito, se muestra tal
        // cual, nunca como error genérico.
        setFeedback({ kind: 'info', message })
      } else {
        setFeedback({
          kind: 'success',
          message: 'Comando enviado — los cambios deberían aplicarse en el diagrama en unos segundos.',
        })
      }
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
