import { useState } from 'react'
import './VoiceToolbar.css'

// Placeholder de Fase 4 (RF-02: Modelado por Comandos de Voz y Texto). Sin lógica
// de voz real todavía -- solo el espacio de layout montado: ícono de micrófono +
// CommandPromptInput (campo de texto alternativo para ambientes con ruido o fallas
// de micrófono, ver RF-02.1), ambos inertes.
export function VoiceToolbar() {
  const [showComingSoon, setShowComingSoon] = useState(false)

  function handleClick() {
    setShowComingSoon(true)
    window.setTimeout(() => setShowComingSoon(false), 2000)
  }

  return (
    <div className="voice-toolbar">
      <button type="button" className="voice-toolbar__button" title="Comandos de voz" onClick={handleClick}>
        🎤
      </button>
      <input
        type="text"
        className="voice-toolbar__command-input"
        placeholder="Escribe un comando… (próximamente)"
        title="CommandPromptInput -- entrada de texto alternativa a la voz (próximamente)"
        disabled
      />
      {showComingSoon && <span className="voice-toolbar__tooltip">Próximamente</span>}
    </div>
  )
}
