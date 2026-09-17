# ${projectName} (app móvil generada)

Esqueleto mínimo de app móvil React Native/Expo generado por UC14 a partir del
diagrama "${diagramName}", con un catálogo de intenciones de voz/texto
(`src/assistant/intents.json`) derivado automáticamente del modelo canónico.

## Limitaciones conocidas (leer antes de la demo)

- **No se compiló ni se corrió con un toolchain de React Native/Flutter real.**
  El entorno donde se generó este proyecto no tiene Android SDK, Xcode ni un
  dispositivo físico -- a diferencia del backend Spring Boot (UC13), que sí se
  valida compilando de verdad con Maven, este esqueleto móvil solo se valida
  estructuralmente (árbol de archivos generado sin excepciones + `intents.json`
  con JSON válido y el esquema esperado). Antes de la demo, correr
  `npm install && npx expo start` en una máquina con el toolchain instalado.
- **El asistente de voz es un placeholder.** `src/assistant/voiceConfig.ts`
  documenta la estrategia elegida (sección 12.2 del plan: `@react-native-voice/voice`
  + Custom Dev Client, con Flutter/`speech_to_text` o el modelo embebido Vosk
  como rutas de contingencia) pero no incluye ninguna integración nativa real:
  STT/TTS reales están fuera del alcance de un generador backend en Java.

## Estructura

```
${projectName}/
├── package.json
├── app.json
├── App.tsx
└── src/
    └── assistant/
        ├── voiceConfig.ts   (placeholder de configuración STT/TTS)
        └── intents.json     (catálogo de intenciones derivado del diagrama)
```

## Entidades incluidas

<#list classes as cls>
- ${cls.entityName} (`/${cls.resourcePathPlural}`)
</#list>
