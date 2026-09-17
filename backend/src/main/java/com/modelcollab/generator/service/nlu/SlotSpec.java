package com.modelcollab.generator.service.nlu;

/**
 * Slot (parámetro) de una intención de voz/texto, derivado de un {@code Attribute}
 * del modelo canónico (sección 12.3 del plan arquitectónico, "Derivación Automática
 * del Metamodelo de Intenciones").
 *
 * @param name     nombre del slot (camelCase, igual al nombre de campo Java del atributo)
 * @param type     una de {@code "DATE"}, {@code "DATETIME"}, {@code "DECIMAL"},
 *                 {@code "INTEGER"}, {@code "VARCHAR"} -- ver mapeo completo y sus
 *                 simplificaciones documentadas en {@link com.modelcollab.generator.service.NluIntentGeneratorService}
 * @param required si el slot es obligatorio para completar la intención
 */
public record SlotSpec(String name, String type, boolean required) {
}
