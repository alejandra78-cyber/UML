import React from 'react';
import { SafeAreaView, ScrollView, Text, View, Button, StyleSheet } from 'react-native';

/**
 * Esqueleto minimo generado para "${projectName}" (UC14 -- Generar Aplicacion Movil).
 *
 * El asistente de voz (STT/TTS) es un PLACEHOLDER: ver src/assistant/voiceConfig.ts
 * y src/assistant/intents.json. La integracion real de reconocimiento de voz
 * offline (paquete @react-native-voice/voice + Custom Dev Client / EAS Build, o
 * el modelo embebido Vosk como contingencia) requiere un dispositivo fisico y un
 * toolchain de React Native que no existe en el entorno donde se generó este
 * proyecto -- ver la limitacion documentada en el reporte de UC14. Este archivo
 * NO fue compilado con Metro/EAS ni corrido en un emulador o dispositivo real.
 */
export default function App() {
  return (
    <SafeAreaView style={styles.container}>
      <ScrollView>
        <Text style={styles.title}>${projectName}</Text>
        <Text style={styles.subtitle}>Entidades disponibles por comando de voz/texto:</Text>
        <#list classes as cls>
        <View style={styles.entityRow}>
          <Text style={styles.entity}>- ${cls.entityName} (/${cls.resourcePathPlural})</Text>
        </View>
        </#list>
        <View style={styles.voiceButton}>
          <Button title="Hablar (placeholder, sin STT real)" onPress={() => {}} />
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, padding: 24 },
  title: { fontSize: 24, fontWeight: 'bold' },
  subtitle: { marginTop: 16, fontSize: 16 },
  entityRow: { marginTop: 4 },
  entity: { fontSize: 14, marginLeft: 8 },
  voiceButton: { marginTop: 24 },
});
