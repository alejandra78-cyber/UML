import 'package:flutter/material.dart';

import '../../core/api_config.dart';
import '../../core/api_exception.dart';
import 'auth_controller.dart';
import 'register_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _formKey = GlobalKey<FormState>();
  final _emailController = TextEditingController();
  final _passwordController = TextEditingController();
  bool _isLoading = false;
  String? _errorMessage;

  @override
  void dispose() {
    _emailController.dispose();
    _passwordController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });
    try {
      await AuthController.instance.login(
        email: _emailController.text.trim(),
        password: _passwordController.text,
      );
    } on ApiException catch (e) {
      setState(() => _errorMessage = e.message);
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _editBaseUrl() async {
    await showDialog<void>(
      context: context,
      builder: (_) => const _BackendUrlDialog(),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Iniciar sesión'),
        actions: [
          IconButton(
            tooltip: 'Configurar backend',
            icon: const Icon(Icons.settings),
            onPressed: _editBaseUrl,
          ),
        ],
      ),
      body: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(24),
          child: Form(
            key: _formKey,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 400),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(Icons.hub_outlined, size: 64),
                  const SizedBox(height: 8),
                  Text(
                    'ModelCollab',
                    style: Theme.of(context).textTheme.headlineSmall,
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 24),
                  TextFormField(
                    controller: _emailController,
                    decoration: const InputDecoration(
                      labelText: 'Email',
                      border: OutlineInputBorder(),
                    ),
                    keyboardType: TextInputType.emailAddress,
                    autocorrect: false,
                    validator: (value) {
                      if (value == null || value.trim().isEmpty) return 'Ingresá tu email';
                      if (!value.contains('@')) return 'Email inválido';
                      return null;
                    },
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _passwordController,
                    decoration: const InputDecoration(
                      labelText: 'Contraseña',
                      border: OutlineInputBorder(),
                    ),
                    obscureText: true,
                    validator: (value) {
                      if (value == null || value.isEmpty) return 'Ingresá tu contraseña';
                      return null;
                    },
                  ),
                  const SizedBox(height: 16),
                  if (_errorMessage != null) ...[
                    Text(
                      _errorMessage!,
                      style: TextStyle(color: Theme.of(context).colorScheme.error),
                    ),
                    const SizedBox(height: 12),
                  ],
                  FilledButton(
                    onPressed: _isLoading ? null : _submit,
                    child: _isLoading
                        ? const SizedBox(
                            height: 20,
                            width: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Text('Iniciar sesión'),
                  ),
                  const SizedBox(height: 8),
                  TextButton(
                    onPressed: _isLoading
                        ? null
                        : () => Navigator.of(context).push(
                              MaterialPageRoute(builder: (_) => const RegisterScreen()),
                            ),
                    child: const Text('¿No tenés cuenta? Registrate'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Diálogo de configuración de la URL del backend. Cada acción (tocar un
/// preset, o tocar "Guardar" con una URL escrita a mano) persiste DE
/// INMEDIATO en `ApiConfig` y muestra una confirmación sin cerrar el
/// diálogo — a propósito, no queda nada pendiente de un segundo paso: antes,
/// tocar un preset solo llenaba el campo de texto y el guardado real
/// dependía de tocar "Guardar" después, lo que se perdía en silencio si el
/// diálogo se cerraba de otra forma (gesto de "atrás" de Android, tocar
/// fuera del diálogo) sin ningún aviso de que no había quedado guardado.
class _BackendUrlDialog extends StatefulWidget {
  const _BackendUrlDialog();

  @override
  State<_BackendUrlDialog> createState() => _BackendUrlDialogState();
}

class _BackendUrlDialogState extends State<_BackendUrlDialog> {
  late final TextEditingController _controller;
  String? _savedConfirmation;
  bool _isSaving = false;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: ApiConfig.instance.baseUrl);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _save(String url) async {
    if (url.trim().isEmpty || _isSaving) return;
    setState(() {
      _isSaving = true;
      _savedConfirmation = null;
    });
    await ApiConfig.instance.setBaseUrl(url);
    if (!mounted) return;
    setState(() {
      _controller.text = ApiConfig.instance.baseUrl;
      _isSaving = false;
      _savedConfirmation = 'Guardado: ${ApiConfig.instance.baseUrl}';
    });
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('URL del backend'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('Preconfiguradas (tocar para aplicar):', style: TextStyle(fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          for (final preset in ApiConfig.presets)
            _PresetTile(
              preset: preset,
              selected: ApiConfig.instance.baseUrl == preset.url,
              onTap: _isSaving ? null : () => _save(preset.url),
            ),
          const SizedBox(height: 12),
          const Text('O escribí una IP de red (LAN) manualmente:'),
          const SizedBox(height: 4),
          TextField(
            controller: _controller,
            autofocus: true,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(hintText: 'http://192.168.0.103:8080'),
            onSubmitted: _save,
          ),
          const SizedBox(height: 8),
          if (_isSaving)
            const Row(
              children: [
                SizedBox(height: 14, width: 14, child: CircularProgressIndicator(strokeWidth: 2)),
                SizedBox(width: 8),
                Text('Guardando…'),
              ],
            )
          else if (_savedConfirmation != null)
            Row(
              children: [
                const Icon(Icons.check_circle, color: Colors.green, size: 18),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(_savedConfirmation!, style: const TextStyle(color: Colors.green)),
                ),
              ],
            ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cerrar'),
        ),
        FilledButton(
          onPressed: _isSaving ? null : () => _save(_controller.text),
          child: const Text('Guardar'),
        ),
      ],
    );
  }
}

class _PresetTile extends StatelessWidget {
  const _PresetTile({required this.preset, required this.selected, required this.onTap});

  final ApiUrlPreset preset;
  final bool selected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            if (selected) const Padding(
              padding: EdgeInsets.only(top: 2, right: 6),
              child: Icon(Icons.check, size: 16, color: Colors.green),
            ),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('${preset.label} — ${preset.url}', style: const TextStyle(fontWeight: FontWeight.w600)),
                  Text(preset.description, style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
