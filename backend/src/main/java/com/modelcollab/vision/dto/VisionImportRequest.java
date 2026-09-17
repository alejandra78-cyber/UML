package com.modelcollab.vision.dto;

import com.modelcollab.metamodel.model.CanonicalModel;

import java.util.Objects;

/**
 * Entrada interna de {@code VisionOcrService.analyzeSketch} (no es el cuerpo
 * HTTP: el endpoint recibe la imagen como {@code multipart/form-data} porque
 * es lo mas natural para un archivo binario -- ver
 * {@code VisionImportController}). Este record existe para que el controller
 * no tenga que pasar 3-4 parametros sueltos al service y para dejar explicito,
 * en un solo lugar, cual es el contexto completo que necesita Gemini para
 * decidir si una clase detectada en la foto ya existe en el diagrama o es
 * nueva: los bytes de la imagen y el {@link CanonicalModel} actual.
 */
public record VisionImportRequest(byte[] imageBytes, String mimeType, CanonicalModel currentModel) {

    public VisionImportRequest {
        Objects.requireNonNull(imageBytes, "imageBytes es obligatorio");
        Objects.requireNonNull(mimeType, "mimeType es obligatorio");
        Objects.requireNonNull(currentModel, "currentModel es obligatorio");
        if (imageBytes.length == 0) {
            throw new IllegalArgumentException("imageBytes no puede estar vacio");
        }
    }
}
