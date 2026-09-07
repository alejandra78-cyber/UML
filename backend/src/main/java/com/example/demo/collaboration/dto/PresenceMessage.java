package com.example.demo.collaboration.dto;

import java.util.UUID;

/**
 * Mensaje de presencia difundido a {@code /topic/diagrams/{id}}: altas/bajas de
 * usuarios en la sala y actualizaciones de cursor/seleccion (operacion catalogada
 * como USER_CURSOR, canal volatil, sin persistencia ni exclusion mutua).
 *
 * @param type       tipo de evento de presencia
 * @param diagramId  diagrama al que pertenece la sala
 * @param userId     usuario asociado al evento
 * @param userName   nombre para mostrar (para pintar avatares/cursores remotos)
 * @param color      color asignado al usuario en la sesion de colaboracion
 * @param cursorX    coordenada X del cursor (solo relevante para CURSOR_UPDATE)
 * @param cursorY    coordenada Y del cursor (solo relevante para CURSOR_UPDATE)
 * @param selectedId id del elemento actualmente seleccionado por el usuario, o null
 */
public record PresenceMessage(
        Type type,
        UUID diagramId,
        UUID userId,
        String userName,
        String color,
        Double cursorX,
        Double cursorY,
        UUID selectedId
) {
    public enum Type {
        JOINED,
        LEFT,
        CURSOR_UPDATE
    }
}
