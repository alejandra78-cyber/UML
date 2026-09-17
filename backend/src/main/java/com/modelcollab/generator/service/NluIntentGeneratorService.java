package com.modelcollab.generator.service;

import com.modelcollab.generator.service.nlu.IntentSpec;
import com.modelcollab.generator.service.nlu.IntentsDocument;
import com.modelcollab.generator.service.nlu.SlotSpec;
import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * UC14 -- Generar Aplicación Móvil (RF-07), catálogo de intenciones NLU derivado
 * (RF-08.3 / sección 12.3 del plan arquitectónico: "Derivación Automática del
 * Metamodelo de Intenciones"). Dado un {@link CanonicalModel} YA VALIDADO (el
 * llamador -- {@code GeneratorController} -- es responsable de correr
 * {@code MetamodelValidator} antes), deriva 4 intenciones por cada
 * {@link ClassEntity}: {@code CREAR_<E>}, {@code LISTAR_<E>}, {@code BUSCAR_<E>},
 * {@code ELIMINAR_<E>}, con disparadores en español y slots derivados de los
 * {@link Attribute} de la clase.
 *
 * <p>El JSON se produce serializando un modelo Java intermedio
 * ({@link IntentsDocument}/{@link IntentSpec}/{@link SlotSpec}) con el mismo
 * {@code ObjectMapper} (Jackson 3, {@code tools.jackson.databind}) que usa el
 * resto de la aplicación -- nunca por concatenación de cadenas.</p>
 *
 * <p><b>Simplificaciones deliberadas (documentadas, no bugs):</b></p>
 * <ul>
 *   <li><b>Sin concordancia de género/número en español</b> ("un/una", "el/la"):
 *   igual que la limitación ya documentada de {@link NameUtils#pluralize}, los
 *   disparadores generados son gramaticalmente razonables pero no perfectos para
 *   todos los sustantivos (p.ej. "nuevo cita" en vez de "nueva cita"). Aceptable
 *   para una demo; una versión futura podría anotar el género en el modelo canónico.</li>
 *   <li><b>Mapeo de slots</b> (sección 12.3 del plan): {@code DATE}→{@code "DATE"},
 *   {@code DATETIME}→{@code "DATETIME"}, {@code DECIMAL}→{@code "DECIMAL"},
 *   {@code INTEGER}/{@code BIGINT}→{@code "INTEGER"}, {@code VARCHAR}/{@code TEXT}→
 *   {@code "VARCHAR"}. {@code BOOLEAN} y {@code UUID} no tienen resolutor de slot
 *   dedicado en el plan; se mapean a {@code "VARCHAR"} como mejor esfuerzo.</li>
 *   <li><b>Slots por acción:</b> {@code CREAR} incluye todos los atributos no-PK
 *   ({@code required} = {@code !isNullable()}, igual semántica que el generador
 *   de backend); {@code LISTAR} no lleva slots (igual que el ejemplo de la sección
 *   12.3); {@code BUSCAR} reutiliza los mismos slots que {@code CREAR} pero todos
 *   opcionales (un criterio de búsqueda parcial); {@code ELIMINAR} usa los
 *   atributos marcados {@code isUnique()} como identificador (obligatorios), o si
 *   la clase no declara ninguno, cae de vuelta a todos los atributos no-PK como
 *   obligatorios (mejor esfuerzo best-effort, documentado).</li>
 * </ul>
 */
@Service
public class NluIntentGeneratorService {

    private final ObjectMapper objectMapper;

    public NluIntentGeneratorService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @return el {@code intents.json} completo, como {@link String} JSON con
     *         indentación (para que sea legible dentro del ZIP descargado).
     */
    public String generateIntentsJson(CanonicalModel model) {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(buildIntentsDocument(model));
    }

    /**
     * Construye el modelo Java intermedio (sin serializar), expuesto por
     * separado para que los tests puedan verificar la estructura sin tener que
     * volver a parsear el JSON.
     */
    public IntentsDocument buildIntentsDocument(CanonicalModel model) {
        List<IntentSpec> intents = new ArrayList<>();
        for (ClassEntity classEntity : model.classes()) {
            intents.addAll(buildIntentsForClass(classEntity));
        }
        return new IntentsDocument(intents);
    }

    private List<IntentSpec> buildIntentsForClass(ClassEntity classEntity) {
        String entityName = NameUtils.pascalCase(classEntity.name());
        String entityUpper = NameUtils.snakeCase(classEntity.name()).toUpperCase(Locale.ROOT);
        String entityPhrase = entityName.toLowerCase(Locale.ROOT);

        List<SlotSpec> createSlots = buildCreateSlots(classEntity);
        List<SlotSpec> searchSlots = createSlots.stream()
                .map(s -> new SlotSpec(s.name(), s.type(), false))
                .toList();
        List<SlotSpec> deleteSlots = buildDeleteSlots(classEntity, createSlots);

        return List.of(
                new IntentSpec("CREAR_" + entityUpper, entityName, "CREATE",
                        createTriggers(entityPhrase), createSlots),
                new IntentSpec("LISTAR_" + entityUpper, entityName, "LIST",
                        listTriggers(entityPhrase), List.of()),
                new IntentSpec("BUSCAR_" + entityUpper, entityName, "SEARCH",
                        searchTriggers(entityPhrase), searchSlots),
                new IntentSpec("ELIMINAR_" + entityUpper, entityName, "DELETE",
                        deleteTriggers(entityPhrase), deleteSlots)
        );
    }

    private List<SlotSpec> buildCreateSlots(ClassEntity classEntity) {
        List<SlotSpec> slots = new ArrayList<>();
        for (Attribute attribute : classEntity.attributes()) {
            if (attribute.isPrimaryKey()) {
                continue; // la PK es interna/autogenerada, no se dicta por voz
            }
            slots.add(new SlotSpec(NameUtils.camelCase(attribute.name()),
                    slotTypeFor(attribute.type()), !attribute.isNullable()));
        }
        return slots;
    }

    private List<SlotSpec> buildDeleteSlots(ClassEntity classEntity, List<SlotSpec> createSlots) {
        List<Attribute> uniqueAttributes = classEntity.attributes().stream()
                .filter(a -> a.isUnique() && !a.isPrimaryKey())
                .toList();
        if (uniqueAttributes.isEmpty()) {
            // Sin atributo identificador declarado: mejor esfuerzo, se piden todos
            // los atributos como obligatorios para poder ubicar el registro a borrar.
            return createSlots.stream().map(s -> new SlotSpec(s.name(), s.type(), true)).toList();
        }
        return uniqueAttributes.stream()
                .map(a -> new SlotSpec(NameUtils.camelCase(a.name()), slotTypeFor(a.type()), true))
                .toList();
    }

    private static String slotTypeFor(AttributeType type) {
        return switch (type) {
            case DATE -> "DATE";
            case DATETIME -> "DATETIME";
            case DECIMAL -> "DECIMAL";
            case INTEGER, BIGINT -> "INTEGER";
            case VARCHAR, TEXT -> "VARCHAR";
            case BOOLEAN, UUID -> "VARCHAR"; // sin resolutor dedicado en el plan; mejor esfuerzo
        };
    }

    private static List<String> createTriggers(String entityPhrase) {
        return List.of(
                "crea " + entityPhrase,
                "crear " + entityPhrase,
                "nuevo " + entityPhrase,
                "agrega " + entityPhrase,
                "registra " + entityPhrase
        );
    }

    private static List<String> listTriggers(String entityPhrase) {
        return List.of(
                "muestra los " + entityPhrase,
                "muestra las " + entityPhrase,
                "lista " + entityPhrase,
                "ver " + entityPhrase,
                "cuáles son los " + entityPhrase
        );
    }

    private static List<String> searchTriggers(String entityPhrase) {
        return List.of(
                "busca " + entityPhrase,
                "buscar " + entityPhrase,
                "encuentra " + entityPhrase,
                "dónde está " + entityPhrase
        );
    }

    private static List<String> deleteTriggers(String entityPhrase) {
        return List.of(
                "elimina " + entityPhrase,
                "borra " + entityPhrase,
                "elimina el " + entityPhrase,
                "quita " + entityPhrase
        );
    }
}
