/// Espejo en Dart de `com.modelcollab.ai.dto.VoiceCommandResponse` (backend)
/// y de la lógica de `VoiceToolbar.tsx` (`buildFeedback`) del frontend web.
/// UC08 (voz) y UC09 (texto) comparten el mismo endpoint y la misma
/// respuesta — la única diferencia es el canal que produjo el texto.
class VoiceCommandResponse {
  const VoiceCommandResponse({
    required this.guardrailRejected,
    required this.guardrailMessage,
    required this.appliedCount,
    required this.rejectedCount,
    required this.truncated,
    required this.rejectedReasons,
  });

  final bool guardrailRejected;
  final String? guardrailMessage;
  final int appliedCount;
  final int rejectedCount;
  final bool truncated;
  final List<String> rejectedReasons;

  factory VoiceCommandResponse.fromJson(Map<String, dynamic> json) => VoiceCommandResponse(
        guardrailRejected: json['guardrailRejected'] as bool? ?? false,
        guardrailMessage: json['guardrailMessage'] as String?,
        appliedCount: (json['appliedCount'] as num?)?.toInt() ?? 0,
        rejectedCount: (json['rejectedCount'] as num?)?.toInt() ?? 0,
        truncated: json['truncated'] as bool? ?? false,
        rejectedReasons: (json['rejectedReasons'] as List<dynamic>? ?? []).cast<String>(),
      );
}

enum AiCommandFeedbackKind { success, partial, guardrail, error }

class AiCommandFeedback {
  const AiCommandFeedback({required this.kind, required this.message});

  final AiCommandFeedbackKind kind;
  final String message;
}

/// Espejo de `buildFeedback` en `web/src/features/ia-asistida/VoiceToolbar.tsx`.
/// El cambio real en el diagrama llega por el broadcast STOMP normal (Fase 2);
/// esta respuesta HTTP es solo el resumen de confirmación.
AiCommandFeedback buildAiCommandFeedback(VoiceCommandResponse response) {
  if (response.guardrailRejected) {
    // El guardrail SIEMPRE responde 200 (es un resultado de negocio válido,
    // no un error) -- nunca se llamó al proveedor de IA, no hay broadcast
    // STOMP que esperar en este caso.
    return AiCommandFeedback(
      kind: AiCommandFeedbackKind.guardrail,
      message: response.guardrailMessage ?? 'El asistente no puede aplicar ese pedido.',
    );
  }

  final parts = <String>[
    response.appliedCount == 1 ? '1 operación aplicada.' : '${response.appliedCount} operaciones aplicadas.',
  ];
  if (response.truncated) {
    parts.add('El asistente de IA interpretó más de 5 operaciones; se aplicaron solo las primeras 5.');
  }
  if (response.rejectedCount > 0) {
    final rejectedLabel = response.rejectedCount == 1 ? '1 operación rechazada' : '${response.rejectedCount} operaciones rechazadas';
    parts.add('$rejectedLabel: ${response.rejectedReasons.join('; ')}');
  }

  return AiCommandFeedback(
    kind: (response.rejectedCount > 0 || response.truncated) ? AiCommandFeedbackKind.partial : AiCommandFeedbackKind.success,
    message: parts.join(' '),
  );
}
