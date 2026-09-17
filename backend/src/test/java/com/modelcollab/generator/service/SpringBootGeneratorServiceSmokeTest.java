package com.modelcollab.generator.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.OwningSide;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.service.IdentifierSanitizer;
import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Smoke test de la sección 4.1 del plan arquitectónico ("de mayor retorno del
 * proyecto"): genera 3 backends Spring Boot de referencia (barbería, veterinaria,
 * inventario) a partir de {@link CanonicalModel} construidos a mano, y los COMPILA
 * DE VERDAD invocando Maven como subproceso sobre cada directorio temporal
 * generado. Si un dominio no compila, el test falla mostrando stdout/stderr real
 * de Maven para ese dominio -- no hay forma de que este test pase con una plantilla
 * FreeMarker rota o con un mapeo JPA inválido.
 *
 * <p>Es un test lento a propósito (invoca Maven 3 veces, una por dominio); el
 * timeout de 10 minutos por dominio es generoso para no dar falsos negativos en
 * una máquina fría de CI.</p>
 */
class SpringBootGeneratorServiceSmokeTest {

    private final SpringBootGeneratorService generatorService = newGeneratorService();

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void barberia_dominioGenerado_compilaConMavenReal() throws Exception {
        assertDomainCompiles("barberia", barberiaModel());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void veterinaria_dominioGenerado_compilaConMavenReal() throws Exception {
        assertDomainCompiles("veterinaria", veterinariaModel());
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    void inventario_dominioGenerado_compilaConMavenReal() throws Exception {
        assertDomainCompiles("inventario", inventarioModel());
    }

    // ------------------------------------------------------------------
    // Infraestructura del test: generar + invocar Maven + limpiar.
    // ------------------------------------------------------------------

    private void assertDomainCompiles(String domainName, CanonicalModel model) throws IOException {
        Path projectRoot = generatorService.generate(model, "com.generated." + domainName, domainName + "-backend");
        boolean keepForInspection = "true".equals(System.getProperty("generator.smoketest.keepOutput"));
        try {
            if (keepForInspection) {
                System.out.println("[" + domainName + "] proyecto generado en: " + projectRoot);
            }
            MavenResult result = runMavenCompile(projectRoot);
            if (result.exitCode() != 0) {
                fail("El backend generado para el dominio '" + domainName + "' NO compiló (exit=" + result.exitCode()
                        + "). Proyecto generado en: " + projectRoot + "\n--- stdout/stderr de Maven ---\n" + result.output());
            }
        } finally {
            if (!keepForInspection) {
                deleteRecursively(projectRoot);
            }
        }
    }

    private static MavenResult runMavenCompile(Path projectRoot) throws IOException {
        String mvnExecutable = resolveMavenExecutable();
        ProcessBuilder builder = new ProcessBuilder(mvnExecutable, "-q", "-o", "-DskipTests", "compile");
        builder.directory(projectRoot.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();

        String output;
        try (var in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrumpido esperando a Maven", e);
        }
        return new MavenResult(exitCode, output);
    }

    private record MavenResult(int exitCode, String output) {
    }

    /**
     * Este proyecto no tiene un Maven instalado globalmente en PATH (solo el
     * wrapper {@code mvnw}), así que "mvn" a secas falla al lanzar el subproceso.
     * Se busca, en orden: (1) {@code MAVEN_HOME}/{@code M2_HOME}; (2) la
     * distribución que el propio {@code mvnw} de ESTE backend ya descargó bajo
     * {@code ~/.m2/wrapper/dists/apache-maven-*} (la forma más confiable de tener
     * un Maven real disponible sin depender de la máquina); (3) como último
     * recurso, "mvn"/"mvn.cmd" por si el entorno de CI sí lo expone en PATH.
     */
    private static String resolveMavenExecutable() throws IOException {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        String binName = isWindows ? "mvn.cmd" : "mvn";

        for (String envVar : List.of("MAVEN_HOME", "M2_HOME")) {
            String home = System.getenv(envVar);
            if (home != null && !home.isBlank()) {
                Path candidate = Path.of(home, "bin", binName);
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
        }

        Path wrapperDists = Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists");
        if (Files.isDirectory(wrapperDists)) {
            try (var paths = Files.walk(wrapperDists, 4)) {
                var found = paths.filter(p -> p.getFileName().toString().equals(binName))
                        .filter(Files::isRegularFile)
                        .findFirst();
                if (found.isPresent()) {
                    return found.get().toAbsolutePath().toString();
                }
            }
        }

        return binName;
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

    private static SpringBootGeneratorService newGeneratorService() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        // UC14: el bean real ahora carga desde la raíz "templates" (ver FreeMarkerConfig);
        // este test construye su propia Configuration standalone (sin contexto Spring),
        // así que debe replicar el mismo cambio de raíz para que los nombres de plantilla
        // con prefijo "springboot/" usados por SpringBootGeneratorService sigan resolviendo.
        configuration.setClassLoaderForTemplateLoading(
                SpringBootGeneratorServiceSmokeTest.class.getClassLoader(), "templates");
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        GeneratorModelBuilder modelBuilder = new GeneratorModelBuilder(new IdentifierSanitizer());
        return new SpringBootGeneratorService(configuration, modelBuilder);
    }

    // ------------------------------------------------------------------
    // Los 3 dominios de referencia de la sección 4.1.
    // ------------------------------------------------------------------

    /** Barbería: Cliente, Barbero, Servicio, Turno. Turno -> Cliente/Barbero/Servicio son ManyToOne. */
    private static CanonicalModel barberiaModel() {
        ClassEntity cliente = classOf("Cliente",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("telefono", AttributeType.VARCHAR, true));
        ClassEntity barbero = classOf("Barbero",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("especialidad", AttributeType.VARCHAR, true));
        ClassEntity servicio = classOf("Servicio",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("precio", AttributeType.DECIMAL, false));
        ClassEntity turno = classOf("Turno",
                attr("fechaHora", AttributeType.DATETIME, false),
                attr("confirmado", AttributeType.BOOLEAN, false));
        // método de negocio de ejemplo, ejercitando el stub del catálogo (invariante 5)
        turno = withMethod(turno, "cancelar", "void");
        turno = withMethod(turno, "calcularTotal", "BigDecimal");

        Relationship turnoCliente = manyToOne(turno.id(), cliente.id());
        Relationship turnoBarbero = manyToOne(turno.id(), barbero.id());
        Relationship turnoServicio = manyToOne(turno.id(), servicio.id());

        return new CanonicalModel("1.0.0", 1, List.of(),
                List.of(cliente, barbero, servicio, turno),
                List.of(turnoCliente, turnoBarbero, turnoServicio));
    }

    /** Veterinaria: Dueño, Mascota, Veterinario, Cita. Mascota->Dueño ManyToOne, Cita->Mascota/Veterinario ManyToOne. */
    private static CanonicalModel veterinariaModel() {
        ClassEntity dueno = classOf("Dueno",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("direccion", AttributeType.VARCHAR, true));
        ClassEntity mascota = classOf("Mascota",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("especie", AttributeType.VARCHAR, false));
        ClassEntity veterinario = classOf("Veterinario",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("matricula", AttributeType.VARCHAR, true));
        ClassEntity cita = classOf("Cita",
                attr("fecha", AttributeType.DATE, false),
                attr("motivo", AttributeType.TEXT, true));
        cita = withMethod(cita, "reprogramar", "void");

        Relationship mascotaDueno = manyToOne(mascota.id(), dueno.id());
        Relationship citaMascota = manyToOne(cita.id(), mascota.id());
        Relationship citaVeterinario = manyToOne(cita.id(), veterinario.id());

        return new CanonicalModel("1.0.0", 1, List.of(),
                List.of(dueno, mascota, veterinario, cita),
                List.of(mascotaDueno, citaMascota, citaVeterinario));
    }

    /**
     * Inventario: Producto, Proveedor, Almacen, Movimiento. Movimiento->Producto/Almacen
     * ManyToOne, y Producto&lt;-&gt;Proveedor ManyToMany (owningSide=SOURCE en Producto).
     */
    private static CanonicalModel inventarioModel() {
        ClassEntity producto = classOf("Producto",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("sku", AttributeType.VARCHAR, false));
        ClassEntity proveedor = classOf("Proveedor",
                attr("razonSocial", AttributeType.VARCHAR, false));
        ClassEntity almacen = classOf("Almacen",
                attr("nombre", AttributeType.VARCHAR, false),
                attr("ubicacion", AttributeType.VARCHAR, true));
        ClassEntity movimiento = classOf("Movimiento",
                attr("cantidad", AttributeType.INTEGER, false),
                attr("fecha", AttributeType.DATETIME, false));
        movimiento = withMethod(movimiento, "revertir", "void");

        Relationship movimientoProducto = manyToOne(movimiento.id(), producto.id());
        Relationship movimientoAlmacen = manyToOne(movimiento.id(), almacen.id());
        Relationship productoProveedor = Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(producto.id())
                .targetClassId(proveedor.id())
                .type(RelationshipType.MANY_TO_MANY)
                .owningSide(OwningSide.SOURCE)
                .joinTableName("producto_proveedor")
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ZERO_MANY)
                .build();

        return new CanonicalModel("1.0.0", 1, List.of(),
                List.of(producto, proveedor, almacen, movimiento),
                List.of(movimientoProducto, movimientoAlmacen, productoProveedor));
    }

    // ------------------------------------------------------------------
    // Helpers de construcción de modelo canónico.
    // ------------------------------------------------------------------

    private static ClassEntity classOf(String name, Attribute... attributes) {
        return ClassEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(attributes))
                .build();
    }

    private static ClassEntity withMethod(ClassEntity c, String methodName, String returnType) {
        var method = new com.modelcollab.metamodel.model.Method(UUID.randomUUID(), methodName, returnType,
                Visibility.PUBLIC, List.of());
        List<com.modelcollab.metamodel.model.Method> methods = new java.util.ArrayList<>(c.methods());
        methods.add(method);
        return ClassEntity.builder()
                .id(c.id())
                .name(c.name())
                .visibility(c.visibility())
                .isAbstract(c.isAbstract())
                .position(c.position())
                .width(c.width())
                .height(c.height())
                .attributes(c.attributes())
                .methods(methods)
                .build();
    }

    private static Attribute attr(String name, AttributeType type, boolean nullable) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(type)
                .visibility(Visibility.PRIVATE)
                .nullable(nullable)
                .build();
    }

    /** Relación ManyToOne "clásica": muchas instancias de {@code manySideId} por una de {@code oneSideId}. */
    private static Relationship manyToOne(UUID manySideId, UUID oneSideId) {
        return Relationship.builder()
                .id(UUID.randomUUID())
                .sourceClassId(manySideId)
                .targetClassId(oneSideId)
                .type(RelationshipType.ASSOCIATION)
                .sourceMultiplicity(Multiplicity.ZERO_MANY)
                .targetMultiplicity(Multiplicity.ONE_ONE)
                .build();
    }
}
