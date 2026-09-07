package com.example.demo.auth.security;

import com.example.demo.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Reads a {@code Bearer} JWT from the {@code Authorization} header, validates
 * it with {@link JwtService}, and -- if valid -- populates the
 * {@link SecurityContextHolder} with an authenticated principal (the
 * authenticated user's id, as a {@link java.util.UUID}) so downstream
 * controllers can read {@code authentication.getPrincipal()}.
 *
 * <p>An absent, malformed or expired token simply leaves the request
 * unauthenticated; it is up to the {@code SecurityFilterChain} authorization
 * rules to reject access to protected endpoints in that case.</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX) && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                JwtService.Claims claims = jwtService.parseAndValidate(token);
                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.userId(), null, List.of());
                authentication.setDetails(claims.email());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtService.JwtException ignored) {
                // Invalid/expired token: leave request unauthenticated.
            }
        }
        filterChain.doFilter(request, response);
    }
}
