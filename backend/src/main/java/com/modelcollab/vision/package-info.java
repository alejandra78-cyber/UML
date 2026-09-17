/**
 * Importar Diagrama desde Foto de Pizarra (UC10), via vision por computadora
 * multimodal (Gemini 2.0 Flash). Corresponde a PKG-03 -- Modelado Asistido por
 * IA del catalogo de casos de uso.
 *
 * <p><b>Alcance deliberadamente limitado a extraccion, no a mutacion (RF-03.3,
 * flujo Human-in-the-Loop):</b> este paquete SOLO analiza una imagen y produce
 * un borrador ({@link com.modelcollab.vision.dto.DraftModelResponse}) para que
 * el frontend lo muestre en un modal de revision. Nunca escribe sobre un
 * {@code Diagram} persistido. La insercion real del borrador confirmado por el
 * usuario reutiliza la operacion {@code BULK_MERGE} (modo {@code DIAGRAM_LOCK})
 * ya implementada por otro caso de uso: {@code CanonicalModelMutator.bulkMerge},
 * disparada por el frontend via el canal STOMP existente
 * ({@code CollaborationStompController}, {@code /app/diagram/{id}/mutate}) recien
 * cuando el usuario confirma en el lienzo -- ver el javadoc de
 * {@link com.modelcollab.vision.dto.DraftModelResponse#toBulkMergePayload()}.</p>
 */
package com.modelcollab.vision;
