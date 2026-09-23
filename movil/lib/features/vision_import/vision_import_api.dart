import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';
import 'package:mime/mime.dart';

import '../../core/api_config.dart';
import '../../core/api_exception.dart';
import '../../core/http_error.dart';
import '../auth/auth_controller.dart';
import 'vision_import_models.dart';

/// `POST /api/v1/diagrams/{id}/vision-import` (CU12, multipart). El backend
/// solo acepta `image/jpeg` o `image/png` en un part llamado exactamente
/// `image` (`VisionImportController.SUPPORTED_CONTENT_TYPES`).
class VisionImportApi {
  const VisionImportApi();

  Future<DraftModelResponse> analyzeSketch(String diagramId, File imageFile) async {
    final token = AuthController.instance.session.value?.token;
    if (token == null) {
      throw const ApiException('No hay sesión activa.');
    }

    final uri = Uri.parse('${ApiConfig.instance.baseUrl}/api/v1/diagrams/$diagramId/vision-import');
    final contentType = lookupMimeType(imageFile.path) ?? 'image/jpeg';

    final request = http.MultipartRequest('POST', uri)
      ..headers['Authorization'] = 'Bearer $token'
      ..files.add(await http.MultipartFile.fromPath(
        'image',
        imageFile.path,
        contentType: MediaType.parse(contentType),
      ));

    http.StreamedResponse streamed;
    try {
      // El análisis de una imagen con un modelo multimodal puede tardar bastante
      // más que un comando de texto (ai-command ya usa 30s; acá damos margen extra).
      streamed = await request.send().timeout(const Duration(seconds: 45));
    } catch (_) {
      throw ApiException(
        'No se pudo conectar a $uri. Revisá la URL del backend y que el servidor esté corriendo.',
      );
    }

    final response = await http.Response.fromStream(streamed);

    if (response.statusCode >= 200 && response.statusCode < 300) {
      return DraftModelResponse.fromJson(jsonDecode(response.body) as Map<String, dynamic>);
    }

    throw ApiException(extractApiErrorMessage(response, statusMessages: const {
      400: 'La imagen no es válida (se esperaba JPEG o PNG).',
      403: 'Necesitás ser miembro con permisos de edición para importar una foto.',
      404: 'No se encontró el diagrama.',
      503: 'El asistente de IA está temporalmente saturado, probá de nuevo en unos minutos.',
    }));
  }
}
