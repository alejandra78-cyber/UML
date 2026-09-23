import '../auth/auth_http_client.dart';
import 'ai_command_models.dart';

/// `POST /api/v1/diagrams/{id}/ai-command` (UC08/UC09). Mismo endpoint para
/// texto (CU11) y voz (CU10) — ambos ya llegan acá como texto plano.
///
/// 403/404/503 son los únicos errores HTTP reales de este endpoint (rol
/// insuficiente, diagrama inexistente, o proveedor de IA saturado tras
/// reintentos con backoff del lado del backend) — el guardrail en cambio
/// siempre responde 200 (ver [buildAiCommandFeedback]). Mismo mapeo de
/// mensajes que `VoiceToolbar.tsx`.
class AiCommandApi {
  const AiCommandApi();

  final AuthHttpClient _http = const AuthHttpClient();

  Future<VoiceCommandResponse> sendCommand(String diagramId, String command) async {
    final json = await _http.post(
      '/api/v1/diagrams/$diagramId/ai-command',
      {'command': command},
      // Una respuesta de IA real puede tardar bastante más que un CRUD normal.
      timeout: const Duration(seconds: 30),
      statusMessages: const {
        403: 'Necesitás ser miembro con permisos de edición para usar comandos de IA.',
        404: 'No se encontró el diagrama.',
        503: 'El asistente de IA está temporalmente saturado, probá de nuevo en unos minutos.',
      },
    ) as Map<String, dynamic>;
    return VoiceCommandResponse.fromJson(json);
  }
}
