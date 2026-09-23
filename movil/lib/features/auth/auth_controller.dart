import 'package:flutter/foundation.dart';

import '../../core/auth_session.dart';
import '../../core/secure_session_store.dart';
import 'auth_api.dart';

/// Estado de sesión a nivel de app. `session.value == null` significa
/// "sin loguear"; la raíz de la app (main.dart) escucha este notifier para
/// decidir entre mostrar LoginScreen o HomeScreen.
class AuthController {
  AuthController._internal();

  static final AuthController instance = AuthController._internal();

  final AuthApi _api = const AuthApi();
  final ValueNotifier<AuthSession?> session = ValueNotifier<AuthSession?>(null);

  Future<void> bootstrap() async {
    session.value = await SecureSessionStore.instance.read();
  }

  Future<void> login({required String email, required String password}) async {
    final result = await _api.login(email: email, password: password);
    await SecureSessionStore.instance.save(result);
    session.value = result;
  }

  Future<void> register({
    required String email,
    required String password,
    required String fullName,
  }) async {
    final result = await _api.register(email: email, password: password, fullName: fullName);
    await SecureSessionStore.instance.save(result);
    session.value = result;
  }

  Future<void> logout() async {
    await SecureSessionStore.instance.clear();
    session.value = null;
  }
}
