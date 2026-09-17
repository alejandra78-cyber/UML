package com.modelcollab.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * CORS para desarrollo local del frontend web (sección 5.1: cliente Vite en
 * {@code http://localhost:<puerto>}, backend en {@code http://localhost:8080}).
 *
 * <p>Sin esta configuración, el navegador bloquea el preflight de las llamadas REST
 * (p.ej. {@code POST /api/v1/auth/login}) por falta de cabeceras
 * {@code Access-Control-Allow-Origin}. El handshake STOMP ({@code /ws-stomp}, en
 * {@code WebSocketConfig}) ya admite cualquier origen ({@code setAllowedOriginPatterns("*")})
 * y no depende de esta clase.</p>
 *
 * <p>Se usa un patrón ({@code setAllowedOriginPatterns}, no {@code setAllowedOrigins})
 * porque Vite no siempre arranca en el puerto 5173: si ese puerto ya está ocupado
 * (p.ej. por otra instancia de {@code npm run dev} corriendo en paralelo), Vite
 * salta automáticamente al siguiente puerto libre (5174, 5175, ...). Fijar un único
 * origen exacto rompe el login cada vez que eso pasa.</p>
 *
 * <p>{@link org.springframework.security.config.Customizer#withDefaults()} en
 * {@code SecurityConfig} recoge automáticamente este bean por tipo
 * ({@link CorsConfigurationSource}), sin necesidad de inyectarlo explícitamente.</p>
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }
}
