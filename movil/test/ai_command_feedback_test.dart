import 'package:flutter_test/flutter_test.dart';
import 'package:modelcollab_mobile/features/ai_command/ai_command_models.dart';

VoiceCommandResponse _response({
  bool guardrailRejected = false,
  String? guardrailMessage,
  int appliedCount = 0,
  int rejectedCount = 0,
  bool truncated = false,
  List<String> rejectedReasons = const [],
}) =>
    VoiceCommandResponse(
      guardrailRejected: guardrailRejected,
      guardrailMessage: guardrailMessage,
      appliedCount: appliedCount,
      rejectedCount: rejectedCount,
      truncated: truncated,
      rejectedReasons: rejectedReasons,
    );

void main() {
  group('buildAiCommandFeedback', () {
    test('guardrail rechazado: usa el mensaje literal del backend, sin contar operaciones', () {
      final feedback = buildAiCommandFeedback(
        _response(guardrailRejected: true, guardrailMessage: 'No puedo borrar toda la base de datos.'),
      );

      expect(feedback.kind, AiCommandFeedbackKind.guardrail);
      expect(feedback.message, 'No puedo borrar toda la base de datos.');
    });

    test('guardrail rechazado sin mensaje: cae a un mensaje genérico', () {
      final feedback = buildAiCommandFeedback(_response(guardrailRejected: true));

      expect(feedback.kind, AiCommandFeedbackKind.guardrail);
      expect(feedback.message, isNotEmpty);
    });

    test('todo aplicado sin rechazos: success, singular con 1 operación', () {
      final feedback = buildAiCommandFeedback(_response(appliedCount: 1));

      expect(feedback.kind, AiCommandFeedbackKind.success);
      expect(feedback.message, '1 operación aplicada.');
    });

    test('todo aplicado sin rechazos: success, plural con varias operaciones', () {
      final feedback = buildAiCommandFeedback(_response(appliedCount: 3));

      expect(feedback.kind, AiCommandFeedbackKind.success);
      expect(feedback.message, '3 operaciones aplicadas.');
    });

    test('con operaciones rechazadas: partial, incluye los motivos', () {
      final feedback = buildAiCommandFeedback(
        _response(appliedCount: 2, rejectedCount: 1, rejectedReasons: const ['target inexistente']),
      );

      expect(feedback.kind, AiCommandFeedbackKind.partial);
      expect(feedback.message, contains('2 operaciones aplicadas.'));
      expect(feedback.message, contains('1 operación rechazada'));
      expect(feedback.message, contains('target inexistente'));
    });

    test('truncado por el tope de 5 operaciones: partial, aunque no haya rechazos', () {
      final feedback = buildAiCommandFeedback(_response(appliedCount: 5, truncated: true));

      expect(feedback.kind, AiCommandFeedbackKind.partial);
      expect(feedback.message, contains('más de 5 operaciones'));
    });
  });
}
