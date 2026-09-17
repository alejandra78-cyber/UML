package com.modelcollab.ai.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Guardrail antialucinacion RF-02.6 ("La IA No Genera Diagramas Masivos"): clasificador
 * <b>deterministico</b> (no depende de si Gemini responde algo o no -- corre ANTES de
 * llamar a Gemini) que detecta la intencion {@code GENERATE_DOMAIN} y rechaza la orden
 * con el mensaje literal del plan arquitectonico (seccion RF-02, extension
 * "Rechazar Generacion Masiva").
 *
 * <h2>Heuristica</h2>
 * Se bloquea un comando si, tras normalizarlo (minusculas + sin tildes/diacriticos),
 * <b>ambas</b> condiciones se cumplen:
 * <ol>
 *   <li>Contiene un <b>verbo generico de creacion masiva</b> (crea/creame/hazme/genera/
 *   generame/arma/armame/construy.../disena.../necesito/quiero/dame).</li>
 *   <li>Contiene un <b>sustantivo de dominio completo</b> (sistema/modelo/aplicacion/app/
 *   plataforma/software/programa/base de datos).</li>
 * </ol>
 * y, ademas, <b>no</b> contiene ningun <b>indicador de comando puntual</b> (clase/atributo/
 * metodo/relacion/herencia/asociacion/agregacion/composicion, en singular o plural): la
 * presencia de vocabulario especifico del metamodelo UML/ER se interpreta como evidencia
 * de que el usuario ya esta dando una instruccion concreta, aunque tambien mencione la
 * palabra "sistema" (p.ej. "Agrega la clase Factura al sistema de facturacion").
 *
 * <h2>Limitaciones conocidas (honestas, no un clasificador perfecto)</h2>
 * <ul>
 *   <li><b>Falsos negativos:</b> un comando de generacion masiva que tambien nombre una
 *   clase o atributo puntual se deja pasar (p.ej. "Hazme un sistema bancario con una
 *   clase Cliente" no se bloquea, porque "clase" esta presente). Mitigacion posible a
 *   futuro: contar sustantivos de dominio + verbos genericos vs. cuantas entidades
 *   especificas se nombran, no solo su presencia binaria.</li>
 *   <li><b>Falsos positivos:</b> un comando puntual que use la palabra "sistema" o
 *   "modelo" en un sentido no relacionado al metamodelo, sin mencionar vocabulario UML
 *   explicito, puede bloquearse indebidamente (p.ej. "Crea un modelo de datos para
 *   guardar pedidos" podria interpretarse como GENERATE_DOMAIN pese a ser relativamente
 *   puntual). No existe forma de eliminar este tipo de ambiguedad con un clasificador
 *   basado en heuristicas lexicas sin invocar al propio LLM -- y este guardrail debe ser
 *   deterministico e independiente de Gemini por diseño (RF-02.6), asi que se acepta el
 *   costo.</li>
 *   <li>No cubre sinonimos fuera de las listas cerradas (p.ej. "confeccionar", "elaborar")
 *   ni ingles ("build me a banking system"): el alcance del proyecto es comandos en
 *   espanol (seccion 1 del documento de arquitectura).</li>
 * </ul>
 */
@Component
public class GenerateDomainGuardrail {

    /** Mensaje de rechazo literal exigido por RF-02.6 (seccion 5 del plan arquitectonico). */
    public static final String REJECTION_MESSAGE =
            "Soy un asistente de edición puntual, no genero dominios completos desde cero. "
                    + "Por favor indícame clases, atributos o relaciones específicas.";

    private static final Pattern GENERIC_CREATION_VERB = Pattern.compile(
            "\\b(crea\\w*|hazme|haz|genera\\w*|arma\\w*|construy\\w*|disena\\w*|necesito|quiero|dame)\\b");

    private static final Pattern FULL_DOMAIN_NOUN = Pattern.compile(
            "\\b(sistema\\w*|modelo\\w*|aplicacion\\w*|app|plataforma\\w*|software|programa\\w*|"
                    + "base\\s+de\\s+datos)\\b");

    private static final Pattern SPECIFIC_INDICATOR = Pattern.compile(
            "\\b(clases?|atributos?|metodos?|relaciones?|relacion|herencia|asociacion(es)?|"
                    + "agregacion(es)?|composicion(es)?)\\b");

    /**
     * Clasifica un comando de voz/texto ya transcripto a texto plano.
     *
     * @param rawCommand comando tal cual lo escribio/dicto el usuario
     * @return resultado bloqueado (con {@link #REJECTION_MESSAGE}) si se detecta
     *         {@code GENERATE_DOMAIN}, o no bloqueado en caso contrario
     */
    public GuardrailResult classify(String rawCommand) {
        String normalized = normalize(rawCommand);

        boolean hasCreationVerb = GENERIC_CREATION_VERB.matcher(normalized).find();
        boolean hasDomainNoun = FULL_DOMAIN_NOUN.matcher(normalized).find();
        boolean hasSpecificIndicator = SPECIFIC_INDICATOR.matcher(normalized).find();

        boolean blocked = hasCreationVerb && hasDomainNoun && !hasSpecificIndicator;
        return blocked ? GuardrailResult.reject() : GuardrailResult.allow();
    }

    private String normalize(String text) {
        if (text == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    /**
     * @param blocked true si el comando debe rechazarse por GENERATE_DOMAIN
     * @param message {@link #REJECTION_MESSAGE} si {@code blocked}, null en caso contrario
     */
    public record GuardrailResult(boolean blocked, String message) {
        static GuardrailResult reject() {
            return new GuardrailResult(true, REJECTION_MESSAGE);
        }

        static GuardrailResult allow() {
            return new GuardrailResult(false, null);
        }
    }
}
