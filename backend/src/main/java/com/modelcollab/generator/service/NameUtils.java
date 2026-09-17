package com.modelcollab.generator.service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Utilidades de conversión de nombres (PascalCase/camelCase/snake_case) usadas por
 * {@link GeneratorModelBuilder} para derivar identificadores Java y SQL a partir de
 * los nombres libres que un usuario puede escribir en el lienzo (con espacios,
 * guiones, etc.). No reemplaza a {@code IdentifierSanitizer} (que resuelve
 * colisiones con palabras reservadas): esta clase solo normaliza formato.
 */
final class NameUtils {

    private static final Pattern WORD_BOUNDARY = Pattern.compile("[^A-Za-z0-9]+");
    private static final Pattern CAMEL_BOUNDARY = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    private NameUtils() {
    }

    /** Convierte un nombre libre en PascalCase válido para un nombre de clase Java. */
    static String pascalCase(String raw) {
        String[] words = splitWords(raw);
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            sb.append(capitalize(word));
        }
        String result = sb.toString();
        return result.isEmpty() ? "Entidad" : ensureValidJavaIdentifierStart(result);
    }

    /** Convierte un nombre libre en camelCase válido para un nombre de campo/variable Java. */
    static String camelCase(String raw) {
        String pascal = pascalCase(raw);
        String camel = Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
        return ensureValidJavaIdentifierStart(camel);
    }

    /** Convierte un nombre libre (o un PascalCase) en snake_case en minúsculas, para tablas/columnas. */
    static String snakeCase(String raw) {
        String[] words = splitWords(raw);
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (sb.length() > 0) {
                sb.append('_');
            }
            sb.append(word.toLowerCase(Locale.ROOT));
        }
        return sb.length() == 0 ? "campo" : sb.toString();
    }

    /**
     * Pluralización simplista (agrega "s", o "es" tras s/x/z/ch/sh) suficiente para
     * nombres de recurso REST de demostración; no pretende cubrir irregulares del
     * español ni del inglés (limitación deliberada, ver reporte final del generador).
     */
    static String pluralize(String singularLowerCase) {
        if (singularLowerCase.endsWith("s") || singularLowerCase.endsWith("x")
                || singularLowerCase.endsWith("z") || singularLowerCase.endsWith("ch")
                || singularLowerCase.endsWith("sh")) {
            return singularLowerCase + "es";
        }
        return singularLowerCase + "s";
    }

    private static String[] splitWords(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        // Primero separa por límites no alfanuméricos, luego por límites camelCase
        // internos (para que "OrderItem" -> ["Order","Item"] y no quede como una sola palabra).
        String[] roughWords = WORD_BOUNDARY.split(raw.trim());
        java.util.List<String> words = new java.util.ArrayList<>();
        for (String rough : roughWords) {
            if (rough.isBlank()) {
                continue;
            }
            for (String sub : CAMEL_BOUNDARY.split(rough)) {
                if (!sub.isBlank()) {
                    words.add(sub);
                }
            }
        }
        return words.toArray(new String[0]);
    }

    private static String capitalize(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String ensureValidJavaIdentifierStart(String identifier) {
        if (Character.isJavaIdentifierStart(identifier.charAt(0))) {
            return identifier;
        }
        return "_" + identifier;
    }

    /**
     * Representa {@code value} como un literal de cadena Java válido (escapando
     * backslashes y comillas), para insertar dentro de las plantillas FreeMarker un
     * texto que YA viene con las comillas de nivel SQL resueltas por
     * {@code IdentifierSanitizer} (p.ej. {@code quotedName} = {@code "order"} con
     * comillas literales incluidas, para el caso de colisión con palabra reservada).
     */
    static String toJavaStringLiteral(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + escaped + "\"";
    }
}
