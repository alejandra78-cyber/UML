import 'dart:convert';

import 'package:stomp_dart_client/stomp_dart_client.dart';

import '../../core/api_config.dart';

enum DiagramConnectionStatus { connecting, connected, reconnecting, error }

typedef DiagramStatusCallback = void Function(DiagramConnectionStatus status, [String? message]);
typedef DiagramBroadcastCallback = void Function(
  String operationType,
  String? targetId,
  Map<String, dynamic> payload,
);

/// Conexión STOMP nativa (sin SockJS) a `/ws-stomp`, suscripta a
/// `/topic/diagrams/{id}`. Mismo contrato que `web/src/collaboration/stompClient.ts`:
/// CONNECT con `Authorization: Bearer <jwt>` como header STOMP nativo
/// (validado por `StompChannelInterceptor`), reconexión automática vía
/// `StompConfig.reconnectDelay` (maneja el "qué pasa si se corta la
/// conexión" pedido para esta fase).
///
/// Filtra del topic los mensajes que NO son `StompBroadcastMessage` reales
/// (LOCK_ACQUIRED/LOCK_RELEASED/PresenceMessage comparten el mismo topic) —
/// fuera de alcance del visor de solo lectura, igual que en el cliente web.
class DiagramStompService {
  StompClient? _client;

  void connect({
    required String diagramId,
    required String token,
    required DiagramStatusCallback onStatusChange,
    required DiagramBroadcastCallback onBroadcast,
  }) {
    disconnect();

    final wsUrl = '${ApiConfig.instance.baseUrl.replaceFirst(RegExp('^http'), 'ws')}/ws-stomp';

    onStatusChange(DiagramConnectionStatus.connecting);

    final client = StompClient(
      config: StompConfig(
        url: wsUrl,
        stompConnectHeaders: {'Authorization': 'Bearer $token'},
        reconnectDelay: const Duration(seconds: 4),
        onConnect: (_) {
          onStatusChange(DiagramConnectionStatus.connected);
          _client?.subscribe(
            destination: '/topic/diagrams/$diagramId',
            callback: (frame) => _handleFrameBody(frame.body, onBroadcast),
          );
        },
        onWebSocketDone: () => onStatusChange(DiagramConnectionStatus.reconnecting),
        onStompError: (frame) =>
            onStatusChange(DiagramConnectionStatus.error, frame.body ?? frame.headers['message']),
        onWebSocketError: (error) => onStatusChange(DiagramConnectionStatus.error, error.toString()),
      ),
    );
    _client = client;
    client.activate();
  }

  void _handleFrameBody(String? body, DiagramBroadcastCallback onBroadcast) {
    if (body == null) return;
    Map<String, dynamic> decoded;
    try {
      decoded = jsonDecode(body) as Map<String, dynamic>;
    } catch (_) {
      return;
    }

    final operationType = decoded['operationType'];
    final sequenceNum = decoded['sequenceNum'];
    if (operationType is! String || sequenceNum is! num) {
      // LOCK_ACQUIRED/LOCK_RELEASED/PresenceMessage: no traen ambos campos a
      // la vez, se ignoran acá (ver comentario de clase).
      return;
    }

    onBroadcast(
      operationType,
      decoded['targetId'] as String?,
      decoded['payload'] as Map<String, dynamic>? ?? const {},
    );
  }

  /// Envía una mutación real a `/app/diagram/{id}/mutate` (mismo contrato
  /// que `sendMutation` en `stompClient.ts`). Usado por CU12 al confirmar un
  /// borrador de importación por foto: las clases/relaciones aceptadas se
  /// mandan como ADD_CLASS/ADD_RELATIONSHIP y vuelven por el broadcast
  /// normal de `/topic/diagrams/{id}`, que ya sabemos aplicar (Fase 2) — no
  /// hace falta mutar el modelo local a mano acá.
  ///
  /// ADD_CLASS/ADD_RELATIONSHIP son `MutualExclusionMode.NONE` (sin lock),
  /// así que no hace falta pedir ningún lock antes de enviarlas.
  ///
  /// Devuelve `false` sin lanzar si no hay conexión activa (se descarta en
  /// silencio, igual que `sendMutation` en el cliente web).
  bool sendMutation({
    required String diagramId,
    required String operationType,
    String? targetId,
    required String userId,
    required Map<String, dynamic> payload,
  }) {
    final client = _client;
    if (client == null || !client.connected) return false;

    client.send(
      destination: '/app/diagram/$diagramId/mutate',
      body: jsonEncode({
        'operationType': operationType,
        'targetId': targetId,
        'userId': userId,
        'clientTimestamp': DateTime.now().millisecondsSinceEpoch,
        'payload': payload,
      }),
    );
    return true;
  }

  bool get isConnected => _client?.connected ?? false;

  void disconnect() {
    _client?.deactivate();
    _client = null;
  }
}
