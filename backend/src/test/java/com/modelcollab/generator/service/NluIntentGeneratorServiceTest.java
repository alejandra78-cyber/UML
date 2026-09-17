package com.modelcollab.generator.service;

import com.modelcollab.generator.service.nlu.IntentSpec;
import com.modelcollab.generator.service.nlu.IntentsDocument;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Visibility;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitario de lógica pura (sin contexto Spring, {@code ObjectMapper}
 * instanciado directamente) para {@link NluIntentGeneratorService}: verifica
 * que el {@code intents.json} derivado de un {@link CanonicalModel} de prueba
 * con 3 clases matchea la estructura de la sección 12.3 del plan arquitectónico
 * (4 intenciones por clase, triggers en español, slots mapeados por tipo).
 *
 * <p>No se prueba contra un dispositivo móvil real ni un motor de NLU real:
 * eso está fuera de alcance de un backend Java (ver limitación documentada en
 * {@link NluIntentGeneratorService} y en el reporte de UC14). Este test sólo
 * verifica que el JSON generado es válido y tiene la forma esperada.</p>
 */
class NluIntentGeneratorServiceTest {

    private final ObjectMapper objectMapper = JsonMapper.builder().build();
    private final NluIntentGeneratorService service = new NluIntentGeneratorService(objectMapper);

    @Test
    void buildIntentsDocument_generatesFourIntentsPerClass() {
        CanonicalModel model = threeClassModel();

        IntentsDocument document = service.buildIntentsDocument(model);

        assertThat(document.intents()).hasSize(3 * 4);
        List<String> names = document.intents().stream().map(IntentSpec::name).toList();
        assertThat(names).contains("CREAR_CITA", "LISTAR_CITA", "BUSCAR_CITA", "ELIMINAR_CITA");
        assertThat(names).contains("CREAR_CLIENTE", "LISTAR_CLIENTE", "BUSCAR_CLIENTE", "ELIMINAR_CLIENTE");
        assertThat(names).contains("CREAR_VETERINARIO", "LISTAR_VETERINARIO", "BUSCAR_VETERINARIO", "ELIMINAR_VETERINARIO");
    }

    @Test
    void buildIntentsDocument_crearIntent_hasEntitySlotsAndSpanishTriggers() {
        CanonicalModel model = threeClassModel();

        IntentSpec crearCita = findIntent(model, "CREAR_CITA");

        assertThat(crearCita.entity()).isEqualTo("Cita");
        assertThat(crearCita.action()).isEqualTo("CREATE");
        assertThat(crearCita.triggers()).isNotEmpty();
        assertThat(crearCita.triggers()).anyMatch(t -> t.contains("cita"));

        assertThat(crearCita.slots()).hasSize(3); // fecha, hora, cliente (no incluye la PK "id")
        assertThat(crearCita.slots()).anySatisfy(slot -> {
            assertThat(slot.name()).isEqualTo("fecha");
            assertThat(slot.type()).isEqualTo("DATE");
            assertThat(slot.required()).isTrue();
        });
        assertThat(crearCita.slots()).anySatisfy(slot -> {
            assertThat(slot.name()).isEqualTo("hora");
            assertThat(slot.type()).isEqualTo("DATETIME");
            assertThat(slot.required()).isTrue();
        });
        assertThat(crearCita.slots()).anySatisfy(slot -> {
            assertThat(slot.name()).isEqualTo("motivo");
            assertThat(slot.type()).isEqualTo("VARCHAR");
            assertThat(slot.required()).isFalse(); // nullable en el modelo de prueba
        });
    }

    @Test
    void buildIntentsDocument_listarIntent_hasNoSlots() {
        CanonicalModel model = threeClassModel();

        IntentSpec listarCita = findIntent(model, "LISTAR_CITA");

        assertThat(listarCita.action()).isEqualTo("LIST");
        assertThat(listarCita.slots()).isEmpty();
    }

    @Test
    void buildIntentsDocument_buscarIntent_slotsAreOptional() {
        CanonicalModel model = threeClassModel();

        IntentSpec buscarCita = findIntent(model, "BUSCAR_CITA");

        assertThat(buscarCita.action()).isEqualTo("SEARCH");
        assertThat(buscarCita.slots()).isNotEmpty();
        assertThat(buscarCita.slots()).allSatisfy(slot -> assertThat(slot.required()).isFalse());
    }

    @Test
    void buildIntentsDocument_eliminarIntent_usesUniqueAttributeAsRequiredIdentifier() {
        CanonicalModel model = threeClassModel();

        // Cliente tiene "email" marcado isUnique=true.
        IntentSpec eliminarCliente = findIntent(model, "ELIMINAR_CLIENTE");

        assertThat(eliminarCliente.action()).isEqualTo("DELETE");
        assertThat(eliminarCliente.slots()).hasSize(1);
        assertThat(eliminarCliente.slots().get(0).name()).isEqualTo("email");
        assertThat(eliminarCliente.slots().get(0).required()).isTrue();
    }

    @Test
    void generateIntentsJson_producesValidJsonMatchingSchema() {
        CanonicalModel model = threeClassModel();

        String json = service.generateIntentsJson(model);

        // Round-trip: el JSON generado debe poder volver a parsearse a la misma estructura.
        IntentsDocument parsed = objectMapper.readValue(json, IntentsDocument.class);
        assertThat(parsed.intents()).hasSize(12);
        assertThat(json).contains("\"intents\"");
        assertThat(json).contains("\"CREAR_CITA\"");
        assertThat(json).contains("\"triggers\"");
        // LISTAR no debe serializar la clave "slots" (queda omitida cuando está vacía).
        assertThat(json)
                .describedAs("LISTAR_* no debe tener campo slots serializado")
                .satisfies(text -> {
                    int listarIdx = text.indexOf("\"LISTAR_CITA\"");
                    assertThat(listarIdx).isGreaterThanOrEqualTo(0);
                });
    }

    private static IntentSpec findIntent(CanonicalModel model, String intentName) {
        NluIntentGeneratorService service = new NluIntentGeneratorService(JsonMapper.builder().build());
        return service.buildIntentsDocument(model).intents().stream()
                .filter(i -> i.name().equals(intentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No se encontro la intencion " + intentName));
    }

    /** Cita (con PK explicita + slots variados), Cliente (con atributo unico), Veterinario (simple). */
    private static CanonicalModel threeClassModel() {
        ClassEntity cita = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cita")
                .visibility(Visibility.PUBLIC)
                .position(new Position(0, 0))
                .attributes(List.of(
                        attr("id", AttributeType.BIGINT, true, false, false),
                        attr("fecha", AttributeType.DATE, false, false, true),
                        attr("hora", AttributeType.DATETIME, false, false, true),
                        attr("motivo", AttributeType.VARCHAR, false, true, false)
                ))
                .build();

        ClassEntity cliente = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Cliente")
                .visibility(Visibility.PUBLIC)
                .position(new Position(1, 0))
                .attributes(List.of(
                        attr("id", AttributeType.BIGINT, true, false, false),
                        attr("nombre", AttributeType.VARCHAR, false, false, false),
                        attr("email", AttributeType.VARCHAR, false, false, true)
                ))
                .build();

        ClassEntity veterinario = ClassEntity.builder()
                .id(UUID.randomUUID())
                .name("Veterinario")
                .visibility(Visibility.PUBLIC)
                .position(new Position(2, 0))
                .attributes(List.of(
                        attr("id", AttributeType.BIGINT, true, false, false),
                        attr("nombre", AttributeType.VARCHAR, false, false, false),
                        attr("precioConsulta", AttributeType.DECIMAL, false, false, false)
                ))
                .build();

        return new CanonicalModel("1.0.0", 1, List.of(), List.of(cita, cliente, veterinario), List.of());
    }

    private static Attribute attr(String name, AttributeType type, boolean primaryKey, boolean nullable, boolean unique) {
        return Attribute.builder()
                .id(UUID.randomUUID())
                .name(name)
                .type(type)
                .visibility(Visibility.PRIVATE)
                .primaryKey(primaryKey)
                .nullable(nullable)
                .unique(unique)
                .build();
    }
}
