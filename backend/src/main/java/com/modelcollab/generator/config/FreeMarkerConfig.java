package com.modelcollab.generator.config;

import freemarker.template.Configuration;
import freemarker.template.TemplateExceptionHandler;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;

/**
 * Bean {@code freemarker.template.Configuration} armado a mano, sin depender de la
 * autoconfiguración de Spring Boot para FreeMarker (que en Spring Boot 4.1.1 puede
 * no existir o llamarse distinto -- ver advertencia técnica del generador UC13).
 *
 * <p><b>UC14 (Generar App Móvil):</b> este único bean {@code Configuration} carga
 * plantillas desde la RAÍZ del classpath {@code templates/} (no desde
 * {@code templates/springboot} como en la versión original de UC13), para que
 * tanto {@code SpringBootGeneratorService} (plantillas bajo {@code templates/springboot/})
 * como {@code MobileAppGeneratorService} (plantillas bajo {@code templates/mobile/})
 * puedan reutilizar el MISMO bean, cada uno resolviendo sus plantillas con el
 * prefijo de subcarpeta correspondiente en el nombre (p.ej.
 * {@code "springboot/pom.xml.ftl"}, {@code "mobile/package.json.ftl"}). Se eligió
 * este approach -- en vez de declarar un segundo {@code @Bean Configuration} --
 * precisamente para no repetir el incidente de Oleada 1 de dos beans/clases en
 * conflicto: con un solo bean de este tipo no hay ambigüedad posible de
 * autowiring por tipo para ningún consumidor futuro.</p>
 */
@org.springframework.context.annotation.Configuration
public class FreeMarkerConfig {

    @Bean
    public Configuration freemarkerConfiguration() {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_34);
        configuration.setClassLoaderForTemplateLoading(getClass().getClassLoader(), "templates");
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        configuration.setLogTemplateExceptions(false);
        configuration.setWrapUncheckedExceptions(true);
        configuration.setFallbackOnNullLoopVariable(false);
        return configuration;
    }
}
