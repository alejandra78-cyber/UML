package com.modelcollab.generator.service;

import com.modelcollab.generator.service.model.MobileClassView;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UC14 -- Generar Aplicación Móvil (RF-07 / sección 12 del plan arquitectónico).
 * Genera, a partir de un {@link CanonicalModel} ya validado, un esqueleto MÍNIMO
 * de proyecto React Native/Expo (sección 17 del plan: "Frontend Móvil: React
 * Native (Custom Dev) / Flutter" -- se eligió React Native por ser lo más
 * mencionado en el documento) en un directorio temporal, más el catálogo
 * {@code intents.json} derivado por {@link NluIntentGeneratorService}.
 *
 * <p>Reutiliza el MISMO bean {@code freemarker.template.Configuration} que
 * {@link SpringBootGeneratorService} (ver {@code FreeMarkerConfig}, que ahora
 * carga plantillas desde la raíz {@code templates/}), resolviendo las suyas con
 * el prefijo {@code "mobile/"}.</p>
 *
 * <p><b>Alcance y limitación deliberada (no es un bug, es de diseño):</b> a
 * diferencia de {@code SpringBootGeneratorService}, cuyo smoke test compila el
 * backend generado con Maven real, este esqueleto móvil NO se compila ni se
 * corre con un toolchain de React Native/Expo real -- no hay Android SDK, Xcode
 * ni un dispositivo físico en este entorno de generación de backend Java. El
 * criterio de "hecho" aquí es puramente estructural: (a) el árbol de archivos se
 * genera sin excepciones, (b) el {@code intents.json} generado es JSON válido
 * con la estructura esperada (esto sí se verifica con un test JUnit normal, ver
 * {@code NluIntentGeneratorServiceTest}). El STT/TTS real (sección 12.2 del
 * plan) queda documentado como PLACEHOLDER en las plantillas generadas
 * (ver {@code templates/mobile/assistant/voiceConfig.ts.ftl}).</p>
 */
@Service
public class MobileAppGeneratorService {

    private final Configuration freemarkerConfiguration;
    private final NluIntentGeneratorService nluIntentGeneratorService;

    public MobileAppGeneratorService(Configuration freemarkerConfiguration,
                                      NluIntentGeneratorService nluIntentGeneratorService) {
        this.freemarkerConfiguration = freemarkerConfiguration;
        this.nluIntentGeneratorService = nluIntentGeneratorService;
    }

    /**
     * @param model       modelo canónico ya validado (mismo invariante que
     *                    {@link SpringBootGeneratorService#generate})
     * @param projectName nombre del proyecto móvil / diagrama de origen; si es
     *                    nulo/blanco se usa {@code "generated-mobile-app"}
     * @return la ruta raíz del proyecto móvil generado, dentro de un directorio
     *         temporal nuevo
     */
    public Path generate(CanonicalModel model, String projectName) throws IOException {
        String resolvedProjectName = normalizeArtifactId(projectName);
        List<MobileClassView> classes = buildClassViews(model);
        String intentsJson = nluIntentGeneratorService.generateIntentsJson(model);

        Path root = Files.createTempDirectory("mobile-gen-");
        Path assistantDir = root.resolve("src/assistant");
        Files.createDirectories(assistantDir);

        Map<String, Object> baseModel = new HashMap<>();
        baseModel.put("projectName", resolvedProjectName);
        baseModel.put("diagramName", projectName == null || projectName.isBlank() ? resolvedProjectName : projectName);
        baseModel.put("classes", classes);

        render("mobile/package.json.ftl", baseModel, root.resolve("package.json"));
        render("mobile/app.json.ftl", baseModel, root.resolve("app.json"));
        render("mobile/App.tsx.ftl", baseModel, root.resolve("App.tsx"));
        render("mobile/README.md.ftl", baseModel, root.resolve("README.md"));
        render("mobile/assistant/voiceConfig.ts.ftl", baseModel, assistantDir.resolve("voiceConfig.ts"));

        Files.writeString(assistantDir.resolve("intents.json"), intentsJson, StandardCharsets.UTF_8);

        return root;
    }

    private List<MobileClassView> buildClassViews(CanonicalModel model) {
        List<MobileClassView> views = new ArrayList<>();
        for (ClassEntity classEntity : model.classes()) {
            String entityName = NameUtils.pascalCase(classEntity.name());
            String resourcePathPlural = NameUtils.pluralize(entityName.toLowerCase());
            views.add(new MobileClassView(entityName, resourcePathPlural));
        }
        return views;
    }

    private void render(String templateName, Map<String, Object> model, Path destination) throws IOException {
        try {
            Template template = freemarkerConfiguration.getTemplate(templateName);
            Files.createDirectories(destination.getParent());
            try (Writer writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
                template.process(model, writer);
            }
        } catch (freemarker.template.TemplateException e) {
            throw new IOException("Error de plantilla FreeMarker '" + templateName + "': " + e.getMessage(), e);
        }
    }

    private static String normalizeArtifactId(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return "generated-mobile-app";
        }
        String cleaned = projectName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "generated-mobile-app" : cleaned;
    }
}
