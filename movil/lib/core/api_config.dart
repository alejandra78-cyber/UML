import 'package:shared_preferences/shared_preferences.dart';

/// Preset de URL para la pantalla de configuración (ver [ApiConfig.presets]).
class ApiUrlPreset {
  const ApiUrlPreset({required this.label, required this.url, required this.description});

  final String label;
  final String url;
  final String description;
}

/// URL base del backend Spring Boot, configurable en runtime — no hay una
/// única URL que sirva para emulador y dispositivo físico a la vez:
/// - Emulador: `10.0.2.2` es el alias fijo que usa el emulador de Android
///   para llegar al localhost de la máquina host.
/// - Dispositivo físico por USB con `adb reverse tcp:8080 tcp:8080`
///   (ver README): el celular reenvía su propio `127.0.0.1:8080` al
///   `127.0.0.1:8080` de la PC. No depende de la red Wi-Fi ni de que la IP
///   LAN cambie, a diferencia de usar la IP de la PC directamente.
/// - Dispositivo físico sin `adb reverse`: la IP LAN de la PC (queda como
///   entrada libre, no hay un valor fijo posible).
class ApiConfig {
  ApiConfig._internal();

  static final ApiConfig instance = ApiConfig._internal();

  static const _prefsKey = 'backend_base_url';

  static const emulatorUrl = 'http://10.0.2.2:8080';

  /// `127.0.0.1`, no `localhost`: en algunos dispositivos Android
  /// "localhost" resuelve primero a `::1` (loopback IPv6) antes de caer a
  /// `127.0.0.1`, y `adb reverse` solo reenvía el loopback IPv4 — usar la IP
  /// literal evita ese intento fallido/lento de más.
  static const adbReverseUrl = 'http://127.0.0.1:8080';

  static const defaultBaseUrl = emulatorUrl;

  static const presets = [
    ApiUrlPreset(
      label: 'Emulador Android',
      url: emulatorUrl,
      description: 'Alias fijo del emulador hacia el localhost de la PC.',
    ),
    ApiUrlPreset(
      label: 'Celular por USB (adb reverse)',
      url: adbReverseUrl,
      description: 'Requiere haber corrido antes: adb reverse tcp:8080 tcp:8080 (ver README).',
    ),
  ];

  String _baseUrl = defaultBaseUrl;

  String get baseUrl => _baseUrl;

  Future<void> load() async {
    final prefs = await SharedPreferences.getInstance();
    _baseUrl = prefs.getString(_prefsKey) ?? defaultBaseUrl;
  }

  Future<void> setBaseUrl(String url) async {
    var normalized = url.trim();
    if (normalized.endsWith('/')) {
      normalized = normalized.substring(0, normalized.length - 1);
    }
    if (normalized.isEmpty) return;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, normalized);
    _baseUrl = normalized;
  }
}
