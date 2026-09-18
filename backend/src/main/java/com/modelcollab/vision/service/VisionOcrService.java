package com.modelcollab.vision.service;

import com.modelcollab.metamodel.model.Attribute;
import com.modelcollab.metamodel.model.AttributeType;
import com.modelcollab.metamodel.model.CanonicalModel;
import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Method;
import com.modelcollab.metamodel.model.Multiplicity;
import com.modelcollab.metamodel.model.Parameter;
import com.modelcollab.metamodel.model.Position;
import com.modelcollab.metamodel.model.Relationship;
import com.modelcollab.metamodel.model.RelationshipType;
import com.modelcollab.metamodel.model.Visibility;
import com.modelcollab.metamodel.service.GridLayoutEngine;
import com.modelcollab.vision.dto.DraftModelResponse;
import com.modelcollab.vision.dto.VisionImportRequest;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * UC10 -- Importar Diagrama desde Foto de Pizarra (RF-03.3). Orquesta el
 * analisis multimodal de una foto de pizarra: arma un prompt estructurado con
 * el {@link CanonicalModel} actual como contexto (para que el modelo pueda
 * decidir si una clase dibujada ya existe o es nueva), se lo pasa a
 * {@link VisionGeminiClient} junto con la imagen, parsea la respuesta JSON y
 * arma un {@link DraftModelResponse} listo para el modal de revision
 * Human-in-the-Loop del frontend.
 *
 * <p>Esta clase NO toca ningun {@code Diagram} persistido ni ningun
 * repositorio: es logica pura de extraccion (imagen -> borrador), igual de
 * testeable de forma aislada que {@code CanonicalModelMutator}. La aplicacion
 * del borrador confirmado por el usuario es responsabilidad exclusiva del
 * flujo {@code BULK_MERGE} ya existente (ver {@code package-info.java} de este
 * paquete).</p>
 */
@Service
public class VisionOcrService {

    private static final Set<String> SUPPORTED_MIME_TYPES = Set.of("image/jpeg", "image/png");

    private final VisionGeminiClient visionGeminiClient;
    private final GridLayoutEngine gridLayoutEngine;
    private final ObjectMapper objectMapper;

    public VisionOcrService(VisionGeminiClient visionGeminiClient, GridLayoutEngine gridLayoutEngine) {
        this.visionGeminiClient = visionGeminiClient;
        this.gridLayoutEngine = gridLayoutEngine;
        // ObjectMapper propio (en vez de inyectar el bean de la app): el texto que
        // devuelve Gemini es JSON de un esquema propio de este prompt (GeminiSketchDraft),
        // completamente distinto del esquema canonico que usa el ObjectMapper compartido
        // (CanonicalModel), asi que no hay ningun beneficio en compartir configuracion.
        this.objectMapper = new ObjectMapper();
    }

    /**
     * @param diagramId id del diagrama sobre el que se pidio el analisis (solo se usa
     *                  para estampar {@link DraftModelResponse#diagramId()}; este metodo
     *                  no lee ni escribe ningun {@code Diagram})
     * @param request   imagen + {@link CanonicalModel} actual (contexto)
     * @return el borrador estructurado, con posiciones ya resueltas para toda clase nueva
     */
    public DraftModelResponse analyzeSketch(UUID diagramId, VisionImportRequest request) {
        if (!SUPPORTED_MIME_TYPES.contains(request.mimeType())) {
            throw new IllegalArgumentException(
                    "Tipo de imagen no soportado: " + request.mimeType() + " (se espera image/jpeg o image/png)");
        }

        String prompt = buildPrompt(request.currentModel());
        String rawResponse = visionGeminiClient.generateContent(request.imageBytes(), request.mimeType(), prompt);
        GeminiSketchDraft sketch = parseSketch(rawResponse);

        List<String> warnings = new ArrayList<>();
        // Mapa "nombre normalizado" -> id, sembrado con las clases YA existentes en el
        // diagrama, para que las relaciones puedan resolver contra clases viejas o nuevas
        // indistintamente por nombre (Gemini no conoce UUIDs de screen, solo nombres/texto).
        Map<String, UUID> idByName = new LinkedHashMap<>();
        for (ClassEntity existing : request.currentModel().classes()) {
            idByName.put(normalize(existing.name()), existing.id());
        }

        List<ClassEntity> newClasses = new ArrayList<>();
        List<UUID> matchedExistingClassIds = new ArrayList<>();

        for (GeminiDraftClass draftClass : sketch.classes()) {
            if (draftClass.name() == null || draftClass.name().isBlank()) {
                warnings.add("Se descarto una clase detectada sin nombre");
                continue;
            }
            UUID existingId = resolveExistingId(draftClass.existingClassId(), request.currentModel());
            if (existingId != null) {
                matchedExistingClassIds.add(existingId);
                idByName.put(normalize(draftClass.name()), existingId);
                continue;
            }

            UUID newId = UUID.randomUUID();
            List<Attribute> attributes = new ArrayList<>();
            for (GeminiDraftAttribute draftAttribute : draftClass.attributes()) {
                if (draftAttribute.name() == null || draftAttribute.name().isBlank()) {
                    warnings.add("Se descarto un atributo sin nombre en la clase '" + draftClass.name() + "'");
                    continue;
                }
                AttributeType type = parseAttributeType(draftAttribute.type(), draftClass.name(), draftAttribute.name(), warnings);
                boolean primaryKey = primaryKeyOrDefault(draftAttribute);
                attributes.add(Attribute.builder()
                        .id(UUID.randomUUID())
                        .name(draftAttribute.name())
                        .type(type)
                        .visibility(Visibility.PRIVATE)
                        .primaryKey(primaryKey)
                        .nullable(!primaryKey)
                        .build());
            }

            List<Method> methods = new ArrayList<>();
            for (GeminiDraftMethod draftMethod : draftClass.methods()) {
                if (draftMethod.name() == null || draftMethod.name().isBlank()) {
                    warnings.add("Se descarto un metodo sin nombre en la clase '" + draftClass.name() + "'");
                    continue;
                }
                List<Parameter> parameters = new ArrayList<>();
                for (GeminiDraftParameter draftParameter : draftMethod.parameters()) {
                    if (draftParameter.name() == null || draftParameter.name().isBlank()) {
                        continue;
                    }
                    String parameterType = blankToDefault(draftParameter.type(), "String");
                    parameters.add(new Parameter(draftParameter.name(), parameterType));
                }
                String returnType = blankToDefault(draftMethod.returnType(), "void");
                // Visibility PUBLIC por defecto: Method no tiene Builder (a diferencia de
                // Attribute/ClassEntity/Relationship) y ni el esquema canonico ni el propio
                // record declaran un default para este campo; PUBLIC es la convencion mas
                // comun para operaciones dibujadas en un boceto de pizarra (interfaces/
                // contratos de clase, ej. patron Composite: operation()/add()/remove()).
                methods.add(new Method(UUID.randomUUID(), draftMethod.name(), returnType, Visibility.PUBLIC, parameters));
            }

            ClassEntity newClass = ClassEntity.builder()
                    .id(newId)
                    .name(draftClass.name())
                    .visibility(Visibility.PUBLIC)
                    .position(new Position(0, 0)) // placeholder; se reasigna abajo via GridLayoutEngine
                    .attributes(attributes)
                    .methods(methods)
                    .build();
            newClasses.add(newClass);
            idByName.put(normalize(draftClass.name()), newId);
        }

        newClasses = applyLayout(request.currentModel(), newClasses);

        List<Relationship> relationships = new ArrayList<>();
        for (GeminiDraftRelationship draftRelationship : sketch.relationships()) {
            UUID sourceId = idByName.get(normalize(draftRelationship.sourceClassName()));
            UUID targetId = idByName.get(normalize(draftRelationship.targetClassName()));
            if (sourceId == null || targetId == null) {
                warnings.add("Se descarto una relacion cuyos extremos no se pudieron resolver: "
                        + draftRelationship.sourceClassName() + " -> " + draftRelationship.targetClassName());
                continue;
            }
            RelationshipType type = parseRelationshipType(draftRelationship.type(), warnings);
            Multiplicity sourceMultiplicity = parseMultiplicity(draftRelationship.sourceMultiplicity(), Multiplicity.ONE_ONE, warnings);
            Multiplicity targetMultiplicity = parseMultiplicity(draftRelationship.targetMultiplicity(), Multiplicity.ZERO_MANY, warnings);

            relationships.add(Relationship.builder()
                    .id(UUID.randomUUID())
                    .sourceClassId(sourceId)
                    .targetClassId(targetId)
                    .type(type)
                    .sourceMultiplicity(sourceMultiplicity)
                    .targetMultiplicity(targetMultiplicity)
                    .isNavigable(true)
                    .build());
        }

        return new DraftModelResponse(diagramId, newClasses, relationships, matchedExistingClassIds, warnings);
    }

    /**
     * Reemplaza la posicion placeholder de cada clase nueva por la que calcula
     * {@link GridLayoutEngine} contra las clases YA existentes en el diagrama
     * (RF-02.5): una foto de pizarra no trae coordenadas de pantalla, asi que
     * esto es la via esperada, no un fallback de excepcion.
     */
    private List<ClassEntity> applyLayout(CanonicalModel currentModel, List<ClassEntity> newClasses) {
        if (newClasses.isEmpty()) {
            return newClasses;
        }
        List<Position> positions = gridLayoutEngine.computePositions(currentModel.classes(), newClasses.size());
        List<ClassEntity> positioned = new ArrayList<>(newClasses.size());
        for (int i = 0; i < newClasses.size(); i++) {
            ClassEntity c = newClasses.get(i);
            positioned.add(ClassEntity.builder()
                    .id(c.id())
                    .name(c.name())
                    .visibility(c.visibility())
                    .isAbstract(c.isAbstract())
                    .position(positions.get(i))
                    .width(c.width())
                    .height(c.height())
                    .attributes(c.attributes())
                    .methods(c.methods())
                    .build());
        }
        return positioned;
    }

    private UUID resolveExistingId(String existingClassId, CanonicalModel currentModel) {
        if (existingClassId == null || existingClassId.isBlank()) {
            return null;
        }
        UUID candidate;
        try {
            candidate = UUID.fromString(existingClassId.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
        return currentModel.findClass(candidate).map(ClassEntity::id).orElse(null);
    }

    private AttributeType parseAttributeType(String rawType, String className, String attributeName, List<String> warnings) {
        if (rawType == null || rawType.isBlank()) {
            return AttributeType.VARCHAR;
        }
        try {
            return AttributeType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warnings.add("Tipo de atributo no reconocido '" + rawType + "' en " + className + "." + attributeName
                    + "; se uso VARCHAR por defecto");
            return AttributeType.VARCHAR;
        }
    }

    private static String blankToDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private RelationshipType parseRelationshipType(String rawType, List<String> warnings) {
        if (rawType == null || rawType.isBlank()) {
            return RelationshipType.ASSOCIATION;
        }
        try {
            return RelationshipType.valueOf(rawType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warnings.add("Tipo de relacion no reconocido '" + rawType + "'; se uso ASSOCIATION por defecto");
            return RelationshipType.ASSOCIATION;
        }
    }

    private Multiplicity parseMultiplicity(String rawLiteral, Multiplicity fallback, List<String> warnings) {
        if (rawLiteral == null || rawLiteral.isBlank()) {
            return fallback;
        }
        try {
            return Multiplicity.fromLiteral(rawLiteral.trim());
        } catch (IllegalArgumentException ex) {
            warnings.add("Multiplicidad no reconocida '" + rawLiteral + "'; se uso " + fallback.literal() + " por defecto");
            return fallback;
        }
    }

    private GeminiSketchDraft parseSketch(String rawResponse) {
        String json = stripMarkdownFence(rawResponse);
        try {
            return objectMapper.readValue(json, GeminiSketchDraft.class);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("La respuesta de Gemini no se pudo interpretar como JSON valido: "
                    + ex.getMessage(), ex);
        }
    }

    /**
     * Gemini a veces envuelve el JSON en una cerca de codigo markdown
     * ({@code ```json ... ```}) a pesar de {@code responseMimeType: application/json};
     * se limpia defensivamente antes de parsear.
     */
    private String stripMarkdownFence(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            trimmed = firstNewline >= 0 ? trimmed.substring(firstNewline + 1) : trimmed;
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
        }
        return trimmed.trim();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Prompt estructurado: le pide a Gemini que devuelva JSON puro (sin
     * coordenadas de pantalla -- una foto de pizarra no tiene ninguna nocion
     * util de "posicion en el lienzo", por eso {@link GridLayoutEngine} resuelve
     * las posiciones despues) siguiendo el esquema de {@link GeminiSketchDraft}.
     * Incluye el {@link CanonicalModel} actual (solo id+nombre de cada clase,
     * lo minimo necesario) para que Gemini pueda marcar {@code existingClassId}
     * en vez de proponer una clase duplicada.
     */
    private String buildPrompt(CanonicalModel currentModel) {
        StringBuilder existingClasses = new StringBuilder("[");
        List<ClassEntity> classes = currentModel.classes();
        for (int i = 0; i < classes.size(); i++) {
            ClassEntity c = classes.get(i);
            if (i > 0) {
                existingClasses.append(",");
            }
            existingClasses.append("{\"id\":\"").append(c.id()).append("\",\"name\":\"")
                    .append(c.name().replace("\"", "\\\"")).append("\"}");
        }
        existingClasses.append("]");

        return """
                Sos un asistente que analiza una foto de una pizarra con un boceto de diagrama
                de clases UML/ER dibujado a mano y lo convierte a JSON estructurado.

                Clases que YA existen en el diagrama actual (para que decidas si una clase de la
                foto ya existe -- en ese caso completa "existingClassId" con ese id exacto -- o es
                nueva -- en ese caso deja "existingClassId" en null):
                %s

                Devolve UNICAMENTE un JSON (sin texto adicional, sin bloques de codigo markdown)
                con exactamente esta forma:
                {
                  "classes": [
                    {
                      "name": "NombreDeClase",
                      "existingClassId": null,
                      "attributes": [
                        { "name": "nombreAtributo", "type": "VARCHAR", "primaryKey": false }
                      ],
                      "methods": [
                        { "name": "nombreMetodo", "returnType": "void", "parameters": [
                          { "name": "nombreParametro", "type": "String" }
                        ] }
                      ]
                    }
                  ],
                  "relationships": [
                    {
                      "sourceClassName": "NombreDeClase",
                      "targetClassName": "OtraClase",
                      "type": "ASSOCIATION",
                      "sourceMultiplicity": "1..1",
                      "targetMultiplicity": "0..*"
                    }
                  ]
                }

                IMPORTANTE: no omitas la lista "methods" de cada clase. Un boceto de UML dibuja
                los metodos/operaciones en el compartimento inferior de cada caja de clase (ej.
                "+ operation()", "+ add(Component)", "# getChild(int): Component") -- detectalos
                igual que los atributos, con su nombre, tipo de retorno si es legible (usa "void"
                si no hay ninguno visible) y sus parametros si los hay (nombre + tipo; si el tipo
                no es legible en la imagen usa "String"). Si una clase realmente no tiene ningun
                metodo dibujado, devolve "methods": [] para esa clase, no omitas la clave.

                Valores validos de "type" de atributo: INTEGER, BIGINT, VARCHAR, TEXT, DECIMAL,
                BOOLEAN, DATE, DATETIME, UUID. Valores validos de "type" de relacion: ASSOCIATION,
                AGGREGATION, COMPOSITION, GENERALIZATION, DEPENDENCY, REALIZATION. Valores validos
                de multiplicidad: "0..1", "1..1", "0..*", "1..*". NO incluyas coordenadas de
                pantalla ni posiciones: no son utiles a partir de una foto de pizarra.
                """.formatted(existingClasses);
    }

    // ---- esquema de parseo del JSON devuelto por Gemini (interno, no expuesto por el paquete) ----

    private record GeminiSketchDraft(List<GeminiDraftClass> classes, List<GeminiDraftRelationship> relationships) {
        private GeminiSketchDraft {
            classes = classes == null ? List.of() : classes;
            relationships = relationships == null ? List.of() : relationships;
        }
    }

    private record GeminiDraftClass(String name, String existingClassId, List<GeminiDraftAttribute> attributes,
                                     List<GeminiDraftMethod> methods) {
        private GeminiDraftClass {
            attributes = attributes == null ? List.of() : attributes;
            methods = methods == null ? List.of() : methods;
        }
    }

    private record GeminiDraftMethod(String name, String returnType, List<GeminiDraftParameter> parameters) {
        private GeminiDraftMethod {
            parameters = parameters == null ? List.of() : parameters;
        }
    }

    private record GeminiDraftParameter(String name, String type) {
    }

    /**
     * {@code primaryKey} es {@link Boolean} (no {@code boolean}) a proposito: este
     * record se deserializa directo del JSON crudo de Gemini via
     * {@code objectMapper.readValue(json, GeminiSketchDraft.class)} -- mismo tipo de
     * fragilidad que el bug real encontrado en {@code AiOperation} (payload con
     * {@code Map.copyOf}), un paso mas atras en la cadena: si Gemini omite el campo o
     * lo devuelve explicitamente {@code null} para un atributo donde no aplica, un
     * {@code boolean} primitivo aqui haria fallar la deserializacion completa de
     * {@link GeminiSketchDraft} con {@code InvalidNullException} antes de llegar
     * siquiera a {@code Attribute.builder()}. Ver {@link #primaryKeyOrDefault(GeminiDraftAttribute)}.
     */
    private record GeminiDraftAttribute(String name, String type, Boolean primaryKey) {
    }

    /** {@code null} (campo omitido/explicito) se trata igual que {@code false}, mismo default que declara el esquema canonico (seccion 7) para {@code isPrimaryKey}. */
    private static boolean primaryKeyOrDefault(GeminiDraftAttribute attribute) {
        return Boolean.TRUE.equals(attribute.primaryKey());
    }

    private record GeminiDraftRelationship(String sourceClassName, String targetClassName, String type,
                                            String sourceMultiplicity, String targetMultiplicity) {
    }
}
