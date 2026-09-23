import 'package:speech_to_text/speech_recognition_error.dart';
import 'package:speech_to_text/speech_recognition_result.dart';
import 'package:speech_to_text/speech_to_text.dart' as stt;

/// Envoltorio sobre `speech_to_text` (STT nativo del dispositivo, CU10).
///
/// Diseño DELIBERADAMENTE de una sola escucha ("single-shot"): tocar el
/// mic dispara UNA sesión; se detiene sola al recibir un resultado final
/// (o al llegar a los 30s), o cuando el usuario la corta a mano — nunca se
/// reinicia por su cuenta.
///
/// Una versión anterior SÍ reiniciaba el listener automáticamente cada vez
/// que el SO cortaba por silencio (pensado para no truncar una frase larga
/// con una pausa), gateado por un flag `_userWantsListening` que recién se
/// apagaba dentro de `onFinalResult`. Eso causó un bug real (reportado en
/// un Tecno KL4s, pero el mecanismo es el mismo en cualquier Android):
/// "sesión terminada" (`done`/`notListening`) y "acá tenés el resultado
/// final" (`onResult` con `finalResult: true`) son dos callbacks
/// SEPARADOS e independientes del SDK nativo, sin garantía de orden ni de
/// ser 1-a-1 — cualquier `done`/`notListening` posterior que llegara
/// mientras el flag siguiera en `true` (tardío, duplicado, o de un
/// `RecognitionService` de fábrica no estándar) reiniciaba la escucha, y
/// como nada más apagaba el flag, el ciclo se repetía indefinidamente
/// hasta salir de la pantalla.
///
/// Para no depender de "adivinar" qué evento es legítimo y cuál es un eco
/// tardío, el auto-reinicio se sacó por completo: el estado de sesión
/// (`_sessionActive`) se apaga ANTES de pedirle al SDK que pare, y todo
/// callback (resultado o cambio de estado) que llegue con la sesión ya
/// cerrada se descarta sin excepción — sea cual sea su origen, nunca puede
/// reactivar ni el ícono ni una escucha nueva por su cuenta.
///
/// Dos advertencias reales de todos modos, documentadas por el propio
/// paquete y por la experiencia de campo con Android:
///
/// (a) Este servicio depende del reconocedor de voz de Google
/// (`RecognitionService` del sistema). Si el usuario no tiene paquetes de
/// idioma descargados u otro motor configurado, NO funciona offline pese a
/// ejecutarse "en el dispositivo" — puede requerir red igual que un
/// `fetch` común. No hay forma de forzarlo 100% offline desde este plugin.
///
/// (b) Android puede cortar el listening tras una pausa de silencio de
/// entre 1 y 3 segundos que el propio doc de `SpeechToText.listen` dice
/// explícitamente que "no puede ser overrideado" (`pauseFor` es un techo,
/// no una garantía). Sin el auto-reinicio, una pausa larga a mitad de
/// frase ahora simplemente termina la escucha con lo que se llegó a
/// transcribir hasta ahí, en vez de reintentar en silencio — es la
/// contrapartida aceptada a cambio de que tocar el mic tenga un
/// comportamiento predecible y nunca se reactive solo.
///
/// NINGUNA de estas dos cosas se puede validar en emulador: no hay
/// micrófono real ni motor de reconocimiento de voz instalado. Solo se
/// puede confirmar en dispositivo físico.
class VoiceRecognitionService {
  final stt.SpeechToText _speech = stt.SpeechToText();

  bool _initialized = false;
  bool _sessionActive = false;
  void Function(String message)? _onError;
  void Function(String transcript)? _onFinalResult;
  void Function(String partial)? _onPartialResult;
  void Function(bool listening)? _onListeningChanged;

  bool get isAvailable => _initialized && _speech.isAvailable;
  bool get isListening => _sessionActive;

  Future<bool> initialize({required void Function(String message) onError}) async {
    if (_initialized) return _speech.isAvailable;
    _onError = onError;
    final available = await _speech.initialize(
      onStatus: _handleStatus,
      onError: _handleError,
    );
    _initialized = true;
    return available;
  }

  Future<void> startListening({
    required void Function(String transcript) onFinalResult,
    void Function(String partial)? onPartialResult,
    void Function(bool listening)? onListeningChanged,
  }) async {
    if (!isAvailable || _sessionActive) return;
    _sessionActive = true;
    _onFinalResult = onFinalResult;
    _onPartialResult = onPartialResult;
    _onListeningChanged = onListeningChanged;

    await _speech.listen(
      onResult: _handleResult,
      listenOptions: stt.SpeechListenOptions(
        partialResults: true,
        cancelOnError: false,
        listenMode: stt.ListenMode.confirmation,
        localeId: 'es-ES',
        listenFor: const Duration(seconds: 30),
      ),
    );
    // `_speech.listen` puede resolver después de que `_endSession` ya haya
    // cerrado la sesión (p.ej. el usuario tocó "detener" mientras arrancaba)
    // — no pisar ese cierre con un `true` tardío.
    if (_sessionActive) {
      _onListeningChanged?.call(true);
    }
  }

  void _handleResult(SpeechRecognitionResult result) {
    if (!_sessionActive) return; // resultado tardío de una sesión ya cerrada: se descarta.
    if (!result.finalResult) {
      _onPartialResult?.call(result.recognizedWords);
      return;
    }
    final transcript = result.recognizedWords.trim();
    _endSession();
    if (transcript.isNotEmpty) {
      _onFinalResult?.call(transcript);
    }
  }

  void _handleStatus(String status) {
    // Sin ninguna condición de reinicio: un `done`/`notListening` acá solo
    // confirma que la sesión terminó (si todavía no la habíamos cerrado
    // nosotros, p.ej. por el corte de silencio del SO) — nunca dispara un
    // nuevo `listen()`. Si la sesión ya estaba cerrada, se ignora entero.
    if (!_sessionActive) return;
    if (status == 'done' || status == 'notListening') {
      _endSession();
    }
  }

  void _handleError(SpeechRecognitionError error) {
    // El paquete documenta que con `cancelOnError: false` (nuestro caso)
    // es responsabilidad del caller llamar a `cancel()` en un error
    // permanente — sin esto, un error podía dejar `_sessionActive` en
    // `true` para siempre y el botón de mic quedaba inutilizable.
    _endSession();
    if (error.permanent) {
      _speech.cancel();
    }
    _onError?.call(_describeError(error));
  }

  Future<void> stopListening() async {
    _endSession();
    await _speech.stop();
  }

  /// Único lugar que apaga la sesión — idempotente: llamarlo de nuevo con
  /// la sesión ya cerrada no hace nada.
  void _endSession() {
    if (!_sessionActive) return;
    _sessionActive = false;
    _onListeningChanged?.call(false);
  }

  void dispose() {
    _endSession();
    _onError = null;
    _onFinalResult = null;
    _onPartialResult = null;
    _onListeningChanged = null;
    _speech.cancel();
  }

  String _describeError(SpeechRecognitionError error) {
    final msg = error.errorMsg;
    if (msg.contains('permission') || msg.contains('Permission')) {
      return 'Falta el permiso de micrófono. Habilitalo en Ajustes del sistema.';
    }
    if (msg.contains('network')) {
      return 'El reconocimiento de voz necesitó red y no la tuvo disponible.';
    }
    return 'Error de reconocimiento de voz: $msg';
  }
}
