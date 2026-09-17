package com.modelcollab.config;

import com.modelcollab.auth.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT-based HTTP security configuration.
 *
 * <p>This is the single new file this task is allowed to add under
 * {@code config/} (see task isolation rules) -- it does not touch
 * {@code WebSocketConfig.java}, which another agent owns. Note that the
 * STOMP handshake endpoint ({@code /ws-stomp/**}, registered in
 * {@code WebSocketConfig}) is left publicly reachable at the HTTP layer:
 * authentication/authorization for collaborative WebSocket sessions is
 * expected to be enforced at the STOMP level (e.g. by
 * {@code collaboration.interceptor.StompChannelInterceptor}, owned by the
 * other agent), not by this HTTP filter chain.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(CsrfConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // Sin este permitAll, el forward interno de Spring MVC a /error (p.ej.
                        // al lanzar ResponseStatusException(401) desde AuthService.login) cae
                        // bajo anyRequest().authenticated(): como esa peticion es anonima, el
                        // Http403ForbiddenEntryPoint por defecto de Spring Security la bloquea
                        // con 403 y body vacio ANTES de que el 401 original llegue a
                        // renderizarse, ocultando el error real al cliente.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/ws-stomp/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
