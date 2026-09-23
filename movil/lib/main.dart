import 'package:flutter/material.dart';

import 'core/api_config.dart';
import 'core/auth_session.dart';
import 'features/auth/auth_controller.dart';
import 'features/auth/login_screen.dart';
import 'features/home/home_screen.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await ApiConfig.instance.load();
  await AuthController.instance.bootstrap();
  runApp(const ModelCollabApp());
}

class ModelCollabApp extends StatelessWidget {
  const ModelCollabApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'ModelCollab',
      theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo), useMaterial3: true),
      home: ValueListenableBuilder<AuthSession?>(
        valueListenable: AuthController.instance.session,
        builder: (context, session, _) {
          return session == null ? const LoginScreen() : HomeScreen(session: session);
        },
      ),
    );
  }
}
