import 'package:flutter/material.dart';

import '../../core/api_exception.dart';
import '../voice/voice_recognition_service.dart';
import 'ai_command_api.dart';
import 'ai_command_models.dart';

/// Barra de comandos de IA (CU10 voz + CU11 texto), pensada para vivir al
/// pie de [DiagramViewScreen]. El resultado de un comando aplicado llega al
/// diagrama por el broadcast STOMP normal de la Fase 2 — esta barra solo
/// dispara el POST y muestra el resumen de confirmación.
class AiCommandBar extends StatefulWidget {
  const AiCommandBar({super.key, required this.diagramId});

  final String diagramId;

  @override
  State<AiCommandBar> createState() => _AiCommandBarState();
}

class _AiCommandBarState extends State<AiCommandBar> {
  final _api = const AiCommandApi();
  final _voiceService = VoiceRecognitionService();
  final _controller = TextEditingController();

  bool _voiceChecked = false;
  bool _voiceAvailable = false;
  bool _isListening = false;
  bool _isSending = false;
  AiCommandFeedback? _feedback;

  @override
  void initState() {
    super.initState();
    _initVoice();
  }

  Future<void> _initVoice() async {
    final available = await _voiceService.initialize(
      onError: (message) {
        if (!mounted) return;
        setState(() {
          _isListening = false;
          _feedback = AiCommandFeedback(kind: AiCommandFeedbackKind.error, message: message);
        });
      },
    );
    if (!mounted) return;
    setState(() {
      _voiceAvailable = available;
      _voiceChecked = true;
    });
  }

  @override
  void dispose() {
    _voiceService.dispose();
    _controller.dispose();
    super.dispose();
  }

  Future<void> _send(String text) async {
    final trimmed = text.trim();
    if (trimmed.isEmpty || _isSending) return;

    setState(() {
      _isSending = true;
      _feedback = null;
    });
    try {
      final response = await _api.sendCommand(widget.diagramId, trimmed);
      if (!mounted) return;
      setState(() {
        _feedback = buildAiCommandFeedback(response);
        _controller.clear();
      });
    } on ApiException catch (e) {
      if (!mounted) return;
      setState(() => _feedback = AiCommandFeedback(kind: AiCommandFeedbackKind.error, message: e.message));
    } finally {
      if (mounted) setState(() => _isSending = false);
    }
  }

  void _toggleMic() {
    if (!_voiceAvailable) return;

    if (_isListening) {
      _voiceService.stopListening();
      setState(() => _isListening = false);
      return;
    }

    setState(() => _isListening = true);
    _voiceService.startListening(
      onListeningChanged: (listening) {
        if (mounted) setState(() => _isListening = listening);
      },
      onPartialResult: (partial) {
        if (mounted) _controller.text = partial;
      },
      onFinalResult: (transcript) {
        if (!mounted) return;
        _controller.text = transcript;
        _voiceService.stopListening();
        setState(() => _isListening = false);
        _send(transcript);
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final feedback = _feedback;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        if (feedback != null) _FeedbackBanner(feedback: feedback, onDismiss: () => setState(() => _feedback = null)),
        Padding(
          padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
          child: Row(
            children: [
              IconButton(
                tooltip: !_voiceChecked
                    ? 'Verificando disponibilidad de voz…'
                    : _voiceAvailable
                        ? (_isListening ? 'Escuchando… (tocá para detener)' : 'Comando de voz')
                        : 'Reconocimiento de voz no disponible en este dispositivo',
                icon: Icon(_isListening ? Icons.mic : Icons.mic_none, color: _isListening ? Colors.red : null),
                onPressed: (_voiceAvailable && !_isSending) ? _toggleMic : null,
              ),
              Expanded(
                child: TextField(
                  controller: _controller,
                  decoration: const InputDecoration(
                    hintText: "Comando (p. ej. 'agregá una clase Cliente')",
                    isDense: true,
                    border: OutlineInputBorder(),
                  ),
                  enabled: !_isSending,
                  onSubmitted: _send,
                ),
              ),
              const SizedBox(width: 4),
              IconButton(
                tooltip: 'Enviar comando',
                icon: _isSending
                    ? const SizedBox(
                        width: 18,
                        height: 18,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    : const Icon(Icons.send),
                onPressed: _isSending ? null : () => _send(_controller.text),
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _FeedbackBanner extends StatelessWidget {
  const _FeedbackBanner({required this.feedback, required this.onDismiss});

  final AiCommandFeedback feedback;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    final color = switch (feedback.kind) {
      AiCommandFeedbackKind.success => Colors.green,
      AiCommandFeedbackKind.partial => Colors.orange,
      AiCommandFeedbackKind.guardrail => Colors.blueGrey,
      AiCommandFeedbackKind.error => Colors.red,
    };
    return Container(
      width: double.infinity,
      color: color.withValues(alpha: 0.12),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: Row(
        children: [
          Expanded(child: Text(feedback.message, style: TextStyle(color: color, fontSize: 13))),
          IconButton(
            icon: const Icon(Icons.close, size: 16),
            onPressed: onDismiss,
            padding: EdgeInsets.zero,
            constraints: const BoxConstraints(),
          ),
        ],
      ),
    );
  }
}
