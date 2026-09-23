import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'auth_session.dart';

/// Guarda el JWT y los datos del usuario logueado en el keystore/keychain
/// nativo (nunca en SharedPreferences ni en texto plano).
class SecureSessionStore {
  SecureSessionStore._internal();

  static final SecureSessionStore instance = SecureSessionStore._internal();

  static const _sessionKey = 'auth_session';

  final _storage = const FlutterSecureStorage();

  Future<AuthSession?> read() async {
    final raw = await _storage.read(key: _sessionKey);
    if (raw == null) return null;
    try {
      return AuthSession.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } on FormatException {
      await clear();
      return null;
    }
  }

  Future<void> save(AuthSession session) async {
    await _storage.write(key: _sessionKey, value: jsonEncode(session.toJson()));
  }

  Future<void> clear() async {
    await _storage.delete(key: _sessionKey);
  }
}
