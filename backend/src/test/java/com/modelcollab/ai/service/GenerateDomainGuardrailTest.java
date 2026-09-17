package com.modelcollab.ai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Calibra la heuristica determinista del guardrail RF-02.6 (ver Javadoc de
 * {@link GenerateDomainGuardrail} para la heuristica exacta y sus limitaciones
 * conocidas). No pretende ser prueba de que la heuristica es perfecta -- solo fija,
 * con casos concretos, el comportamiento que se considera correcto hoy.
 */
class GenerateDomainGuardrailTest {

    private final GenerateDomainGuardrail guardrail = new GenerateDomainGuardrail();

    @ParameterizedTest
    @ValueSource(strings = {
            "Hazme un sistema bancario",
            "Créame un modelo para un hospital",
            "Genera un sistema de inventario completo",
            "Necesito una aplicación completa para gestionar una veterinaria",
            "Diseñame un modelo completo de base de datos para un restaurante"
    })
    void classify_genericFullDomainOrders_areBlockedWithLiteralMessage(String command) {
        GenerateDomainGuardrail.GuardrailResult result = guardrail.classify(command);

        assertThat(result.blocked()).isTrue();
        assertThat(result.message()).isEqualTo(GenerateDomainGuardrail.REJECTION_MESSAGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Crea la clase Factura con atributo total decimal",
            "Agrega el atributo telefono a Proveedor",
            "Relaciona Factura con Cliente de muchos a uno",
            "Mueve la clase Cliente a la derecha",
            "Crea un sistema de facturación con la clase Factura"
    })
    void classify_pointedCommands_areNotBlocked(String command) {
        GenerateDomainGuardrail.GuardrailResult result = guardrail.classify(command);

        assertThat(result.blocked()).isFalse();
        assertThat(result.message()).isNull();
    }

    @Test
    void classify_nullCommand_isNotBlocked() {
        // Defensivo: DTO ya exige @NotBlank en la capa REST, pero el guardrail no debe
        // reventar si se lo invoca directamente (p.ej. desde otro test) con null.
        GenerateDomainGuardrail.GuardrailResult result = guardrail.classify(null);

        assertThat(result.blocked()).isFalse();
    }

    @Test
    void classify_knownFalseNegative_domainOrderThatAlsoNamesASpecificClass_isDocumentedAsNotBlocked() {
        // Limitacion conocida documentada en el Javadoc de GenerateDomainGuardrail: la
        // presencia de "clase" hace que esto NO se bloquee, aunque la intencion real del
        // usuario sea generar un dominio completo. Este test fija ese comportamiento a
        // proposito (no es un bug no descubierto), para que quede explicito y calibrado.
        GenerateDomainGuardrail.GuardrailResult result =
                guardrail.classify("Hazme un sistema bancario con una clase Cliente");

        assertThat(result.blocked()).isFalse();
    }
}
