{
  "name": "${projectName}",
  "version": "1.0.0",
  "private": true,
  "main": "node_modules/expo/AppEntry.js",
  "scripts": {
    "start": "expo start",
    "android": "expo run:android",
    "ios": "expo run:ios"
  },
  "dependencies": {
    "expo": "~51.0.0",
    "expo-speech": "~12.0.0",
    "@react-native-voice/voice": "^3.2.4",
    "react": "18.2.0",
    "react-native": "0.74.0"
  },
  "comment_stt": "expo-speech es solo TTS (sintesis de voz). El STT offline real se resuelve con @react-native-voice/voice + Custom Dev Client (EAS Build), o el modelo embebido Vosk como ruta de contingencia -- ver seccion 12.2 del plan arquitectonico y src/assistant/voiceConfig.ts. No compilado ni verificado contra un toolchain React Native real en este entorno."
}
