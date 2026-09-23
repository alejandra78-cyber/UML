import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../core/api_config.dart';
import '../../core/api_exception.dart';
import '../../core/http_error.dart';
import 'auth_controller.dart';

/// Cliente HTTP para endpoints que requieren `Authorization: Bearer <jwt>`,
/// usando el token de la sesión activa en [AuthController].
class AuthHttpClient {
  const AuthHttpClient();

  static const _defaultStatusMessages = {
    401: 'Sesión expirada. Volvé a iniciar sesión.',
    403: 'No tenés permiso para acceder a este recurso.',
    404: 'No encontrado.',
  };

  Future<dynamic> get(String path, {Map<int, String>? statusMessages}) =>
      _send('GET', path, statusMessages: statusMessages);

  Future<dynamic> post(
    String path,
    Map<String, dynamic> body, {
    Map<int, String>? statusMessages,
    Duration timeout = const Duration(seconds: 10),
  }) =>
      _send('POST', path, body: body, statusMessages: statusMessages, timeout: timeout);

  Future<dynamic> _send(
    String method,
    String path, {
    Map<String, dynamic>? body,
    Map<int, String>? statusMessages,
    Duration timeout = const Duration(seconds: 10),
  }) async {
    final token = AuthController.instance.session.value?.token;
    if (token == null) {
      throw const ApiException('No hay sesión activa.');
    }

    final uri = Uri.parse('${ApiConfig.instance.baseUrl}$path');
    final headers = {
      'Content-Type': 'application/json',
      'Authorization': 'Bearer $token',
    };

    late final http.Response response;
    try {
      response = await (method == 'POST'
              ? http.post(uri, headers: headers, body: jsonEncode(body ?? const {}))
              : http.get(uri, headers: headers))
          .timeout(timeout);
    } catch (_) {
      throw ApiException(
        'No se pudo conectar a $uri. Revisá la URL del backend y que el servidor esté corriendo.',
      );
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      if (response.body.isEmpty) return null;
      return jsonDecode(response.body);
    }

    throw ApiException(
      extractApiErrorMessage(response, statusMessages: statusMessages ?? _defaultStatusMessages),
    );
  }
}
