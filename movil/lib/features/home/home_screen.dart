import 'package:flutter/material.dart';

import '../../core/auth_session.dart';
import '../auth/auth_controller.dart';
import '../diagram/diagram_gate_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.session});

  final AuthSession session;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('ModelCollab'),
        actions: [
          IconButton(
            tooltip: 'Cerrar sesión',
            icon: const Icon(Icons.logout),
            onPressed: () => AuthController.instance.logout(),
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Hola, ${session.fullName}\n${session.email}',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              icon: const Icon(Icons.hub_outlined),
              label: const Text('Ver diagrama en vivo'),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const DiagramGateScreen()),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
