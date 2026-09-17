package com.modelcollab.generator.service;

import com.modelcollab.generator.service.model.ClassView;
import com.modelcollab.metamodel.model.CanonicalModel;
import freemarker.template.Configuration;
import freemarker.template.Template;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Genera, a partir de un {@link CanonicalModel} ya validado, el árbol de archivos de
 * un proyecto Spring Boot independiente (sección 13.1 del plan arquitectónico) en un
 * directorio temporal. No empaqueta el resultado (ver {@link ZipPackagingService}) ni
 * valida el modelo (ver {@code MetamodelValidator}, que debe correrse ANTES de
 * llamar a este servicio -- el controlador es responsable de bloquear la
 * generación si hay errores).
 *
 * <p>Usa plantillas FreeMarker bajo {@code src/main/resources/templates/springboot}
 * (sección 2.3 del plan: plantillas intercambiables) en vez de construir el código
 * Java con concatenación de cadenas. Toda la lógica de mapeo modelo-canónico -&gt;
 * anotaciones JPA vive en {@link GeneratorModelBuilder}; esta clase solo resuelve
 * nombres de paquete/proyecto y escribe archivos.</p>
 *
 * <p><b>UC14:</b> el bean {@code Configuration} inyectado ahora carga plantillas
 * desde la raíz {@code templates/} (ver {@code FreeMarkerConfig}), compartido con
 * {@code MobileAppGeneratorService}; por eso todos los nombres de plantilla usados
 * aquí llevan el prefijo {@code "springboot/"} explícito.</p>
 */
@Service
public class SpringBootGeneratorService {

    private final Configuration freemarkerConfiguration;
    private final GeneratorModelBuilder modelBuilder;

    public SpringBootGeneratorService(Configuration freemarkerConfiguration, GeneratorModelBuilder modelBuilder) {
        this.freemarkerConfiguration = freemarkerConfiguration;
        this.modelBuilder = modelBuilder;
    }

    /**
     * @param model        modelo canónico ya validado (invariantes bloqueantes en OK)
     * @param basePackage  paquete base del proyecto generado, p.ej. {@code "com.empresa.app"};
     *                     si es nulo/blanco se usa {@code "com.generated.app"}
     * @param projectName  nombre del proyecto/artifactId, p.ej. {@code "mi-diagrama"};
     *                     si es nulo/blanco se usa {@code "generated-backend"}
     * @return la ruta raíz del proyecto generado dentro de un directorio temporal nuevo
     */
    public Path generate(CanonicalModel model, String basePackage, String projectName) throws IOException {
        String resolvedBasePackage = normalizePackage(basePackage);
        String resolvedProjectName = normalizeArtifactId(projectName);

        List<ClassView> classes = modelBuilder.build(model);

        Path root = Files.createTempDirectory("springboot-gen-");
        String packagePath = resolvedBasePackage.replace('.', '/');
        Path javaRoot = root.resolve("src/main/java").resolve(packagePath);
        Path resourcesRoot = root.resolve("src/main/resources");

        Files.createDirectories(javaRoot.resolve("domain/model"));
        Files.createDirectories(javaRoot.resolve("repository"));
        Files.createDirectories(javaRoot.resolve("service/dto/mapper"));
        Files.createDirectories(javaRoot.resolve("service/impl"));
        Files.createDirectories(javaRoot.resolve("controller"));
        Files.createDirectories(javaRoot.resolve("config"));
        Files.createDirectories(resourcesRoot);

        Map<String, Object> baseModel = new HashMap<>();
        baseModel.put("basePackage", resolvedBasePackage);
        baseModel.put("projectName", resolvedProjectName);
        baseModel.put("classes", classes);

        // --- Raíz del proyecto ---
        render("springboot/pom.xml.ftl", baseModel, root.resolve("pom.xml"));
        render("springboot/Dockerfile.ftl", baseModel, root.resolve("Dockerfile"));
        render("springboot/docker-compose.yml.ftl", baseModel, root.resolve("docker-compose.yml"));
        render("springboot/application.properties.ftl", baseModel, resourcesRoot.resolve("application.properties"));

        // --- Application.java + GlobalExceptionHandler ---
        render("springboot/Application.java.ftl", baseModel, javaRoot.resolve("Application.java"));
        render("springboot/GlobalExceptionHandler.java.ftl", baseModel, javaRoot.resolve("config/GlobalExceptionHandler.java"));

        // --- Una pasada por clase para las 5 capas ---
        for (ClassView cls : classes) {
            Map<String, Object> classModel = new HashMap<>();
            classModel.put("basePackage", resolvedBasePackage);
            classModel.put("projectName", resolvedProjectName);
            classModel.put("cls", cls);

            render("springboot/Entity.java.ftl", classModel, javaRoot.resolve("domain/model/" + cls.getClassName() + ".java"));
            render("springboot/Repository.java.ftl", classModel, javaRoot.resolve("repository/" + cls.getRepositoryName() + ".java"));
            render("springboot/RequestDTO.java.ftl", classModel, javaRoot.resolve("service/dto/" + cls.getRequestDtoName() + ".java"));
            render("springboot/ResponseDTO.java.ftl", classModel, javaRoot.resolve("service/dto/" + cls.getResponseDtoName() + ".java"));
            render("springboot/Mapper.java.ftl", classModel, javaRoot.resolve("service/dto/mapper/" + cls.getMapperName() + ".java"));
            render("springboot/ServiceInterface.java.ftl", classModel, javaRoot.resolve("service/" + cls.getServiceName() + ".java"));
            render("springboot/ServiceImpl.java.ftl", classModel, javaRoot.resolve("service/impl/" + cls.getServiceImplName() + ".java"));
            render("springboot/Controller.java.ftl", classModel, javaRoot.resolve("controller/" + cls.getControllerName() + ".java"));
        }

        return root;
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

    private static String normalizePackage(String basePackage) {
        if (basePackage == null || basePackage.isBlank()) {
            return "com.generated.app";
        }
        String[] segments = basePackage.trim().toLowerCase().split("\\.");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            String cleaned = segment.replaceAll("[^a-z0-9]", "");
            if (cleaned.isEmpty()) {
                continue;
            }
            if (Character.isDigit(cleaned.charAt(0))) {
                cleaned = "p" + cleaned;
            }
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(cleaned);
        }
        return sb.length() == 0 ? "com.generated.app" : sb.toString();
    }

    private static String normalizeArtifactId(String projectName) {
        if (projectName == null || projectName.isBlank()) {
            return "generated-backend";
        }
        String cleaned = projectName.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return cleaned.isEmpty() ? "generated-backend" : cleaned;
    }
}
