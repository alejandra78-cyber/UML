/**
 * Configuracion PLACEHOLDER del asistente de voz local (sección 12.2 del plan
 * arquitectónico -- "Mitigación Técnica del STT Móvil Offline").
 *
 * NO contiene integración real de reconocimiento de voz: no hay SDK de
 * Android/iOS ni dispositivo físico en el entorno donde se generó este
 * proyecto, así que STT/TTS reales quedan fuera de alcance de un backend Java
 * (ver el reporte de UC14). Este archivo documenta la estrategia elegida en el
 * plan para que el equipo que continúe el proyecto sepa qué cablear:
 *
 *  - TTS (respuesta hablada): `expo-speech`.
 *  - STT Ruta Principal: `@react-native-voice/voice` compilado con un Custom
 *    Dev Client (EAS Build), usando el reconocedor de voz nativo del sistema
 *    con paquete de idioma español descargado en el dispositivo.
 *  - STT Ruta B (contingencia): Flutter + `speech_to_text`.
 *  - STT Ruta C (contingencia): modelo acústico embebido Vosk (~45 MB) en
 *    español, para reconocimiento 100% offline sin depender de Google/Apple.
 */
export const VOICE_CONFIG = {
  ttsEngine: 'expo-speech',
  sttEngine: 'react-native-voice-placeholder',
  locale: 'es-ES',
  intentsFile: './intents.json',
  isRealSttWired: false,
};

export default VOICE_CONFIG;
