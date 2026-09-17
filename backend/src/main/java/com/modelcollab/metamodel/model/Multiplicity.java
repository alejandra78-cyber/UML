package com.modelcollab.metamodel.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Multiplicidad de un extremo de relacion. Se representa como enum pero conserva
 * la literal textual usada en el JSON canonico ("0..1", "1..1", "0..*", "1..*").
 *
 * <p>{@code @JsonValue}/{@code @JsonCreator} hacen que Jackson serialice/deserialice
 * directamente contra la literal (p.ej. {@code "0..1"}) en vez del nombre de la
 * constante ({@code ZERO_ONE}), que es el formato que usa el esquema canonico
 * (seccion 7) y por lo tanto el que envian los payloads de mutacion STOMP.</p>
 */
public enum Multiplicity {
    ZERO_ONE("0..1"),
    ONE_ONE("1..1"),
    ZERO_MANY("0..*"),
    ONE_MANY("1..*");

    private final String literal;

    Multiplicity(String literal) {
        this.literal = literal;
    }

    @JsonValue
    public String literal() {
        return literal;
    }

    @JsonCreator
    public static Multiplicity fromLiteral(String literal) {
        return Arrays.stream(values())
                .filter(m -> m.literal.equals(literal))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Multiplicidad invalida: " + literal));
    }

    @Override
    public String toString() {
        return literal;
    }
}
