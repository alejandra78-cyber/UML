// Tipado mínimo de la Web Speech API para VoiceToolbar (UC08). No forma parte del
// lib.dom.d.ts estándar de TypeScript (sigue siendo una propuesta no ratificada
// del WHATWG, sin @types oficial) -- se declara acá solo el subconjunto que se
// usa, sin agregar ninguna dependencia nueva al proyecto.

interface SpeechRecognitionResultLike {
  readonly transcript: string
}

interface SpeechRecognitionAlternativesLike {
  readonly length: number
  readonly isFinal: boolean
  [index: number]: SpeechRecognitionResultLike
}

interface SpeechRecognitionEventLike extends Event {
  readonly results: {
    readonly length: number
    [index: number]: SpeechRecognitionAlternativesLike
  }
}

interface SpeechRecognitionLike extends EventTarget {
  lang: string
  interimResults: boolean
  continuous: boolean
  start(): void
  stop(): void
  onresult: ((event: SpeechRecognitionEventLike) => void) | null
  onerror: ((event: Event) => void) | null
  onend: (() => void) | null
}

interface Window {
  SpeechRecognition?: new () => SpeechRecognitionLike
  webkitSpeechRecognition?: new () => SpeechRecognitionLike
}
