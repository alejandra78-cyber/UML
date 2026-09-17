package com.modelcollab.generator.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Visibility;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el criterio de "hecho" documentado para UC14 (Generar Aplicación
 * Móvil): (a) el árbol de archivos del esqueleto móvil se genera SIN
 * excepciones a partir de un {@link CanonicalModel} de prueba, y (b) el archivo
 * {@code src/assistant/intents.json} queda escrito y es JSON válido (la
 * estructura fina de ese JSON ya la cubre {@link NluIntentGeneratorServiceTest}).
 *
 * <p><b>Lo que este test NO hace</b> (a diferencia de
 * {@link SpringBootGeneratorServiceSmokeTest}, que compila el backend generado
 * con Maven real): no invoca ningún toolchain de React Native/Expo/Metro/EAS, ni
 * npm install, ni un emulador -- no existen en este entorno. Ver la limitación
 * documentada en {@link MobileAppGeneratorService} y en el reporte de UC14.</p>
 */
class MobileAppGeneratorServiceTest {

    private final MobileAppGeneratorService generatorService = newGeneratorService();

    @Test
    void generate_writesExpectedFileTreeWithoutExceptions() throws IOException {
        CanonicalModel model = twoClassModel();

        Path root = generatorService.generate(model, "Veterinaria Demo");
        try {
            assertThat(root.resolve("package.json")).exists();
            assertThat(root.resolve("app.json")).exists();
            assertThat(root.resolve("App.tsx")).exists();
            assertThat(root.resolve("README.md")).exists();
            assertThat(root.resolve("src/assistant/voiceConfig.ts")).exists();
            assertThat(root.resolve("src/assistant/intents.json")).exists();

            String packageJson = Files.readString(root.resolve("package.json"), StandardCharsets.UTF_8);
            assertThat(packageJson).contains("veterinaria-demo");

            String appTsx = Files.readString(root.resolve("App.tsx"), StandardCharsets.UTF_8);
            assertThat(appTsx).contains("Mascota");
            assertThat(appTsx).contains("Dueno");
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void generate_intentsJsonFile_isValidJsonMatchingSchema() throws IOException {
        CanonicalModel model = twoClassModel();

        Path root = generatorService.generate(model, "Veterinaria Demo");
        try {
            String intentsJson = Files.readString(root.resolve("src/assistant/intents.json"), StandardCharsets.UTF_8);
            var mapper = JsonMapper.builder().build();
            var parsed = mapper.readValue(intentsJson, com.modelcollab.generator.service.nlu.IntentsDocument.class);

            assertThat(parsed.intents()).hasSize(2 * 4); // 2 clases x 4 intenciones (CREAR/LISTAR/BUSCAR/ELIMINAR)
        } finally {
            deleteRecursively(root);
        }
    }

    private static MobileAppGeneratorService newGeneratorService() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setClassLoaderForTemplateLoading(
                MobileAppGeneratorServiceTest.class.getClassLoader(), "templates");
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        NluIntentGeneratorService nluService = new NluIntentGeneratorService(JsonMapper.builder().build());
        return new MobileAppGeneratorService(configuration, nluService);
    }

    private static CanonicalModel twoClassModel() {
        ClassEntity mascota = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Mascota")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(
                        Attribute.builder().id(UUID.randomUUID()).name("id").type(AttributeType.BIGINT)
                                .visibility(Visibility.PRIVATE).primaryKey(true).build(),
                        Attribute.builder().id(UUID.randomUUID()).name("nombre").type(AttributeType.VARCHAR)
                                .visibility(Visibility.PRIVATE).nullable(false).build()
                ))
                .build();
        ClassEntity dueno = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Dueno")
                .visibility(Visibility.PUBLIC)
                .position(new Position(1, 0))
                .attributes(List.of(
                        Attribute.builder().id(UUID.randomUUID()).name("id").type(AttributeType.BIGINT)
                                .visibility(Visibility.PRIVATE).primaryKey(true).build(),
                        Attribute.builder().id(UUID.randomUUID()).name("nombre").type(AttributeType.VARCHAR)
                                .visibility(Visibility.PRIVATE).nullable(false).build()
                ))
                .build();
        return new CanonicalModel("1.0.0", 1, List.of(), List.of(mascota, dueno), List.of());
    }

    private static void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort
                }
            });
        } catch (IOException ignored) {
            // best-effort
        }
    }
}
