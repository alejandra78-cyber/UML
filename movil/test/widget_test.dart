import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:modelcollab_mobile/main.dart';

void main() {
  testWidgets('Muestra la pantalla de login cuando no hay sesión guardada', (
    WidgetTester tester,
  ) async {
    await tester.pumpWidget(const ModelCollabApp());
    await tester.pump();

    expect(find.text('Iniciar sesión'), findsWidgets);
    expect(find.byIcon(Icons.settings), findsOneWidget);
  });
}
