import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../core/api_config.dart';
import '../../core/api_exception.dart';
import '../../core/auth_session.dart';
import '../../core/http_error.dart';

/// Cliente HTTP para `/api/v1/auth/*`, mismo contrato que consume el
/// frontend web (ver AuthController/LoginRequest/RegisterRequest/AuthResponse
/// en backend/src/main/java/com/modelcollab/auth).
class AuthApi {
  const AuthApi();

  Future<AuthSession> login({required String email, required String password}) {
    return _post('/api/v1/auth/login', {
      'email': email,
      'password': password,
    });
  }

  Future<AuthSession> register({
    required String email,
    required String password,
    required String fullName,
  }) {
    return _post('/api/v1/auth/register', {
      'email': email,
      'password': password,
      'fullName': fullName,
    });
  }

  Future<AuthSession> _post(String path, Map<String, dynamic> body) async {
    final uri = Uri.parse('${ApiConfig.instance.baseUrl}$path');
    late final http.Response response;
    try {
      response = await http
          .post(
            uri,
            headers: const {'Content-Type': 'application/json'},
            body: jsonEncode(body),
          )
          .timeout(const Duration(seconds: 10));
    } catch (_) {
      throw ApiException(
        'No se pudo conectar a $uri. Revisá la URL del backend (ícono de '
        'configuración) y que el servidor esté corriendo.',
      );
    }

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return AuthSession.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
    }

    throw ApiException(extractApiErrorMessage(response, statusMessages: const {
      401: 'Email o contraseña incorrectos.',
      409: 'Ese email ya está registrado.',
      400: 'Datos inválidos. Revisá el formulario.',
    }));
  }
}
