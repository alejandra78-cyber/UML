package com.modelcollab.vision.dto;

import com.modelcollab.metamodel.model.ClassEntity;
import com.modelcollab.metamodel.model.Relationship;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Borrador estructurado devuelto por {@code POST /api/v1/diagrams/{diagramId}/vision-import}
 * (RF-03.3, flujo Human-in-the-Loop): el frontend lo muestra en un modal de
 * revision para que el usuario apruebe, edite o descarte lo que Gemini detecto
 * en la foto de pizarra. Este endpoint NUNCA aplica nada sobre el diagrama
 * persistido -- ver {@code VisionImportController} y
 * {@code VisionImportControllerTest#visionImport_neverMutatesPersistedDiagram}.
 *
 * @param diagramId               diagrama sobre el que se pidio el analisis (para que el
 *                                frontend confirme que la respuesta corresponde al lienzo abierto)
 * @param classes                 clases NUEVAS propuestas (id ya asignado, posicion ya resuelta
 *                                por {@link com.modelcollab.metamodel.service.GridLayoutEngine}
 *                                cuando Gemini no pudo inferir una razonable de la foto)
 * @param relationships           relaciones NUEVAS propuestas; {@code sourceClassId}/{@code targetClassId}
 *                                pueden apuntar tanto a un id de {@link #classes()} como a un id
 *                                de una clase YA existente en el diagrama (ver {@link #matchedExistingClassIds()})
 * @param matchedExistingClassIds ids de clases que YA existian en el diagrama y que Gemini
 *                                identifico como correspondientes a algo dibujado en la foto
 *                                (informativo para el modal: "esta clase ya existe, no se duplica")
 * @param warnings                detecciones ambiguas o descartadas (p.ej. una relacion cuyo
 *                                extremo no se pudo resolver a ninguna clase, un tipo de dato
 *                                no reconocido) -- el borrador igual se devuelve completo con
 *                                el resto de lo que si se pudo resolver
 */
public record DraftModelResponse(
        UUID diagramId,
        List<ClassEntity> classes,
        List<Relationship> relationships,
        List<UUID> matchedExistingClassIds,
        List<String> warnings
) {
    public DraftModelResponse {
        classes = classes == null ? List.of() : List.copyOf(classes);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
        matchedExistingClassIds = matchedExistingClassIds == null ? List.of() : List.copyOf(matchedExistingClassIds);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * Arma, sin ninguna transformacion adicional, el {@code payload} que el
     * frontend puede enviar tal cual como operacion {@code BULK_MERGE} (modo
     * {@code DIAGRAM_LOCK}) por el canal STOMP existente
     * ({@code CollaborationStompController} / {@code CanonicalModelMutator.bulkMerge})
     * si el usuario confirma el borrador sin editarlo -- esa es la razon de
     * que {@link #classes()} y {@link #relationships()} ya sean objetos del
     * esquema canonico ({@link ClassEntity}/{@link Relationship}) y no un DTO
     * intermedio propio de este paquete: {@code CanonicalModelMutator.bulkMerge}
     * los deserializa exactamente con esta forma via
     * {@code objectMapper.convertValue(payload.get("classes"), new TypeReference<List<ClassEntity>>() {})}.
     * No incluye {@code packages} porque una foto de pizarra no aporta
     * informacion de paquetes logicos.
     */
    public Map<String, Object> toBulkMergePayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("classes", classes);
        payload.put("relationships", relationships);
        payload.put("packages", List.of());
        return payload;
    }
}
