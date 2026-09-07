package com.example.demo.metamodel.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * Sanea identificadores (nombres de clase/atributo) que colisionan con palabras
 * reservadas de SQL/PostgreSQL o de Java, para que el futuro generador de codigo
 * (fuera de alcance aqui) pueda mapearlos de forma segura a nombres de tabla/columna
 * y campos Java validos.
 */
@Service
public class IdentifierSanitizer {

    /**
     * Palabras reservadas SQL estandar / PostgreSQL mas comunes en el contexto de
     * generacion de esquemas (no exhaustiva, pero cubre los casos mas frecuentes
     * de colision con nombres de dominio tipicos: order, user, group, table...).
     */
    private static final Set<String> SQL_RESERVED_WORDS = Set.of(
            "ALL", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC",
            "ASYMMETRIC", "BOTH", "CASE", "CAST", "CHECK", "COLLATE", "COLUMN",
            "CONSTRAINT", "CREATE", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_USER", "DEFAULT", "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE",
            "END", "EXCEPT", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "GRANT",
            "GROUP", "HAVING", "IN", "INITIALLY", "INTERSECT", "INTO", "LEADING",
            "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "NEW", "NOT", "NULL", "OFF",
            "OFFSET", "OLD", "ON", "ONLY", "OR", "ORDER", "PRIMARY", "REFERENCES",
            "RETURNING", "SELECT", "SESSION_USER", "SOME", "SYMMETRIC", "TABLE",
            "THEN", "TO", "TRAILING", "TRUE", "UNION", "UNIQUE", "USER", "USING",
            "VARIADIC", "WHEN", "WHERE", "WINDOW", "WITH", "KEY", "VALUE", "LEVEL",
            "TYPE", "VIEW", "INDEX", "SCHEMA", "DATABASE", "ROLE"
    );

    /**
     * Palabras reservadas de Java (java.se 21). No exhaustiva pero cubre las
     * colisiones esperables con nombres de atributo del dominio (class, new, int...).
     */
    private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while", "var", "yield", "record",
            "sealed", "permits", "true", "false", "null"
    );

    public boolean isSqlReserved(String identifier) {
        return identifier != null && SQL_RESERVED_WORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    public boolean isJavaReserved(String identifier) {
        return identifier != null && JAVA_RESERVED_WORDS.contains(identifier.toLowerCase(Locale.ROOT));
    }

    /**
     * Devuelve el identificador listo para usar en un nombre de tabla/columna SQL,
     * entrecomillado si colisiona con una palabra reservada.
     */
    public SqlIdentifier sanitizeSqlIdentifier(String rawName) {
        boolean reserved = isSqlReserved(rawName);
        String quoted = reserved ? "\"" + rawName + "\"" : rawName;
        return new SqlIdentifier(rawName, quoted, reserved);
    }

    /**
     * Devuelve un nombre de campo Java valido para un nombre de atributo de dominio,
     * anteponiendo "_" si colisiona con una palabra reservada de Java, preservando
     * el nombre de columna real (sin modificar) para el mapeo JPA/@Column.
     */
    public JavaFieldMapping sanitizeJavaFieldName(String rawName) {
        boolean reserved = isJavaReserved(rawName);
        String javaFieldName = reserved ? "_" + rawName : rawName;
        return new JavaFieldMapping(javaFieldName, rawName, reserved);
    }

    /**
     * Identificador SQL saneado.
     *
     * @param originalName nombre original sin modificar
     * @param quotedName   nombre listo para usar en DDL/@Table/@Column (entrecomillado si aplica)
     * @param isReserved   true si colisiono con una palabra reservada SQL
     */
    public record SqlIdentifier(String originalName, String quotedName, boolean isReserved) {
    }

    /**
     * Mapeo entre el nombre de campo Java (valido) y el nombre de columna real.
     *
     * @param javaFieldName nombre de campo Java valido (puede llevar prefijo "_")
     * @param columnName    nombre de columna real preservado (para @Column(name=...))
     * @param isReserved    true si colisiono con una palabra reservada de Java
     */
    public record JavaFieldMapping(String javaFieldName, String columnName, boolean isReserved) {
    }
}
