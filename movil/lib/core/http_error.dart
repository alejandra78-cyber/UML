import 'dart:convert';

import 'package:http/http.dart' as http;

/// Extrae un mensaje de error legible de una respuesta HTTP no exitosa.
/// Primero intenta leer `detail`/`message`/`error` del cuerpo (Spring Boot
/// ProblemDetail u otro formato de error JSON); si el cuerpo no es JSON o no
/// trae ninguno de esos campos, cae a [statusMessages] (mapeado por
/// statusCode) y por último a un mensaje genérico.
String extractApiErrorMessage(http.Response response, {Map<int, String>? statusMessages}) {
  try {
    final decoded = jsonDecode(response.body);
    if (decoded is Map<String, dynamic>) {
      final detail = decoded['detail'] ?? decoded['message'] ?? decoded['error'];
      if (detail is String && detail.isNotEmpty) return detail;
    }
  } catch (_) {
    // El cuerpo no era JSON parseable; seguimos con el mensaje por status code.
  }
  return statusMessages?[response.statusCode] ?? 'Error del servidor (${response.statusCode}).';
}
