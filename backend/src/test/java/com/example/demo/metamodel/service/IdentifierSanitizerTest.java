package com.example.demo.metamodel.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IdentifierSanitizerTest {

    private final IdentifierSanitizer sanitizer = new IdentifierSanitizer();

    @Test
    void isSqlReserved_trueForKnownReservedWord_caseInsensitive() {
        assertThat(sanitizer.isSqlReserved("order")).isTrue();
        assertThat(sanitizer.isSqlReserved("ORDER")).isTrue();
        assertThat(sanitizer.isSqlReserved("Order")).isTrue();
    }

    @Test
    void isSqlReserved_trueForColumn_regressionForDuplicateElementBug() {
        // "COLUMN" estaba duplicado en el Set.of(...) de SQL_RESERVED_WORDS, lo que
        // hacia fallar la inicializacion estatica de la clase con
        // IllegalArgumentException("duplicate element: COLUMN") y tumbaba el
        // arranque de todo el contexto de Spring. Este test fija el comportamiento
        // correcto para que una futura reintroduccion del duplicado se detecte aqui.
        assertThat(sanitizer.isSqlReserved("column")).isTrue();
        assertThat(sanitizer.isSqlReserved("COLUMN")).isTrue();
    }

    @Test
    void isSqlReserved_falseForNonReservedWord() {
        assertThat(sanitizer.isSqlReserved("cliente")).isFalse();
        assertThat(sanitizer.isSqlReserved(null)).isFalse();
    }

    @Test
    void isJavaReserved_trueForKnownReservedWord() {
        assertThat(sanitizer.isJavaReserved("class")).isTrue();
        assertThat(sanitizer.isJavaReserved("new")).isTrue();
        assertThat(sanitizer.isJavaReserved("int")).isTrue();
        assertThat(sanitizer.isJavaReserved("CLASS")).isTrue();
    }

    @Test
    void isJavaReserved_falseForNonReservedWord() {
        assertThat(sanitizer.isJavaReserved("nombre")).isFalse();
        assertThat(sanitizer.isJavaReserved(null)).isFalse();
    }

    @Test
    void sanitizeSqlIdentifier_quotesReservedWord_preservingOriginalName() {
        IdentifierSanitizer.SqlIdentifier result = sanitizer.sanitizeSqlIdentifier("Order");

        assertThat(result.isReserved()).isTrue();
        assertThat(result.originalName()).isEqualTo("Order");
        assertThat(result.quotedName()).isEqualTo("\"Order\"");
    }

    @Test
    void sanitizeSqlIdentifier_returnsUnquotedForNonReservedWord() {
        IdentifierSanitizer.SqlIdentifier result = sanitizer.sanitizeSqlIdentifier("Cliente");

        assertThat(result.isReserved()).isFalse();
        assertThat(result.originalName()).isEqualTo("Cliente");
        assertThat(result.quotedName()).isEqualTo("Cliente");
    }

    @Test
    void sanitizeJavaFieldName_prependsUnderscore_preservingRealColumnName() {
        IdentifierSanitizer.JavaFieldMapping result = sanitizer.sanitizeJavaFieldName("class");

        assertThat(result.isReserved()).isTrue();
        assertThat(result.javaFieldName()).isEqualTo("_class");
        assertThat(result.columnName()).isEqualTo("class");
    }

    @Test
    void sanitizeJavaFieldName_returnsUnchangedForNonReservedWord() {
        IdentifierSanitizer.JavaFieldMapping result = sanitizer.sanitizeJavaFieldName("nombre");

        assertThat(result.isReserved()).isFalse();
        assertThat(result.javaFieldName()).isEqualTo("nombre");
        assertThat(result.columnName()).isEqualTo("nombre");
    }
}
