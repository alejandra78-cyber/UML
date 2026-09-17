package com.modelcollab.collaboration.interceptor;

import com.modelcollab.auth.service.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interceptor del canal de entrada de mensajes STOMP (registrado en
 * {@code WebSocketConfig#configureClientInboundChannel}). Responsabilidades:
 *
 * <ol>
 *   <li>En CONNECT: valida el JWT real emitido por {@code AuthService}/
 *       {@link JwtService} (header nativo STOMP {@code Authorization: Bearer <jwt>})
 *       y, si es valido, vincula la identidad autenticada (userId, email) a la
 *       sesion STOMP como atributos de sesion y como {@link Principal} (para que
 *       {@code convertAndSendToUser} funcione). Un CONNECT sin token o con token
 *       invalido/expirado se rechaza.</li>
 *   <li>En SEND hacia {@code /app/diagram/{id}/mutate}, {@code /lock},
 *       {@code /presence/join} o {@code /cursor}: resuelve el {@code projectId}
 *       real del diagrama via {@link DiagramProjectResolver} y valida el rol del
 *       usuario en ese proyecto contra {@code project_members.role} usando
 *       {@link ProjectRoleLookup}. Si el rol es VIEWER, rechaza el mensaje
 *       lanzando {@link ViewerNotAllowedException} (el framework la traduce en un
 *       frame STOMP ERROR devuelto solo al emisor; el mensaje nunca llega al
 *       controlador ni se reenvia a la sala).</li>
 * </ol>
 *
 * <p><b>Limitacion conocida:</b> este interceptor autentica la sesion STOMP en
 * CONNECT (userId confiable, derivado del JWT), pero los handlers de
 * {@code CollaborationStompController} todavia leen el campo {@code userId} que
 * el propio cliente incluye en el cuerpo de cada mensaje (p.ej.
 * {@code LockActionRequest.userId()}), en vez de usar el principal ya
 * autenticado de la sesion. Sustituir esos campos por el principal autenticado
 * es un endurecimiento de seguridad pendiente, fuera del alcance de este
 * cambio.</p>
 */
@Component
public class StompChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StompChannelInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private static final Pattern MUTATE_DESTINATION = Pattern.compile("^/app/diagram/([^/]+)/mutate$");
    private static final Pattern LOCK_DESTINATION = Pattern.compile("^/app/diagram/([^/]+)/lock$");
    private static final Pattern PRESENCE_JOIN_DESTINATION = Pattern.compile("^/app/diagram/([^/]+)/presence/join$");
    private static final Pattern CURSOR_DESTINATION = Pattern.compile("^/app/diagram/([^/]+)/cursor$");
    private static final List<Pattern> ROLE_GUARDED_DESTINATIONS =
            List.of(MUTATE_DESTINATION, LOCK_DESTINATION, PRESENCE_JOIN_DESTINATION, CURSOR_DESTINATION);

    private static final String VIEWER_ROLE = "VIEWER";

    /** Paleta fija para asignar un color deterministico por usuario (misma persona = mismo color entre reconexiones). */
    private static final String[] COLOR_PALETTE = {
            "#E53935", "#8E24AA", "#3949AB", "#00897B", "#43A047", "#FB8C00", "#6D4C41", "#00ACC1"
    };

    public static final String SESSION_ATTR_USER_ID = "userId";
    public static final String SESSION_ATTR_USER_NAME = "userName";
    public static final String SESSION_ATTR_COLOR = "color";

    private final Optional<ProjectRoleLookup> projectRoleLookup;
    private final Optional<DiagramProjectResolver> diagramProjectResolver;
    private final JwtService jwtService;

    public StompChannelInterceptor(Optional<ProjectRoleLookup> projectRoleLookup,
                                    Optional<DiagramProjectResolver> diagramProjectResolver,
                                    JwtService jwtService) {
        this.projectRoleLookup = projectRoleLookup;
        this.diagramProjectResolver = diagramProjectResolver;
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        // IMPORTANTE: StompHeaderAccessor.wrap(message) crea SIEMPRE una copia nueva del
        // accessor, desligada del MutableMessageHeaders real que Spring adjunta al mensaje
        // mientras atraviesa la cadena de interceptores. Mutar esa copia (p.ej. accessor.setUser(...)
        // en el CONNECT) no se reflejaba en el mensaje real, por lo que StompSubProtocolHandler
        // nunca vinculaba el Principal a la sesion y todo SEND posterior fallaba con
        // "No hay Principal autenticado" pese a que el CONNECT parecia aceptarse. Se usa en
        // cambio MessageHeaderAccessor.getAccessor(...), que recupera el accessor mutable real
        // ya asociado al mensaje (mismo patron que la documentacion oficial de Spring para
        // autenticar sesiones STOMP en preSend).
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            accessor = StompHeaderAccessor.wrap(message);
        }
        StompCommand command = accessor.getCommand();

        if (command == StompCommand.CONNECT) {
            authenticateConnect(accessor);
            return message;
        }

        if (command == StompCommand.SEND) {
            String destination = accessor.getDestination();
            if (destination != null) {
                UUID diagramId = extractGuardedDiagramId(destination);
                if (diagramId != null) {
                    enforceNotViewer(diagramId, accessor);
                }
            }
        }

        return message;
    }

    /**
     * Valida el JWT enviado en el header nativo {@code Authorization} del frame
     * CONNECT y, si es valido, vincula userId/email/color derivados a la sesion.
     * Rechaza la conexion (lanzando {@link StompAuthenticationException}) si el
     * header falta o el token es invalido/expirado.
     */
    private void authenticateConnect(StompHeaderAccessor accessor) {
        String header = firstNativeHeader(accessor, "Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            log.warn("STOMP CONNECT sin header 'Authorization: Bearer <jwt>'; se rechaza la conexion");
            throw new StompAuthenticationException("Falta token de autenticacion (Authorization: Bearer <jwt>)");
        }

        String token = header.substring(BEARER_PREFIX.length());
        JwtService.Claims claims;
        try {
            claims = jwtService.parseAndValidate(token);
        } catch (JwtService.JwtException ex) {
            log.warn("Token JWT invalido en CONNECT STOMP: {}", ex.getMessage());
            throw new StompAuthenticationException("Token de autenticacion invalido o expirado");
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            log.warn("Sesion STOMP CONNECT sin sessionAttributes disponibles; no se pudo vincular identidad");
            return;
        }

        String userId = claims.userId().toString();
        sessionAttributes.put(SESSION_ATTR_USER_ID, userId);
        sessionAttributes.put(SESSION_ATTR_USER_NAME, claims.email());
        sessionAttributes.put(SESSION_ATTR_COLOR, colorFor(claims.userId()));
        accessor.setUser(new AuthenticatedPrincipal(userId));
        log.debug("Sesion STOMP autenticada para usuario {}", userId);
    }

    private void enforceNotViewer(UUID diagramId, StompHeaderAccessor accessor) {
        if (projectRoleLookup.isEmpty() || diagramProjectResolver.isEmpty()) {
            log.debug("ProjectRoleLookup/DiagramProjectResolver no disponibles aun; se omite la validacion de rol VIEWER");
            return;
        }

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        Object userIdAttr = sessionAttributes == null ? null : sessionAttributes.get(SESSION_ATTR_USER_ID);
        if (userIdAttr == null) {
            log.warn("Mensaje SEND sin userId de sesion autenticada; se rechaza por falta de identidad");
            throw new ViewerNotAllowedException("No se pudo determinar el usuario emisor del mensaje");
        }

        UUID userId;
        try {
            userId = UUID.fromString(userIdAttr.toString());
        } catch (IllegalArgumentException ex) {
            log.warn("userId de sesion con formato invalido: {}", userIdAttr);
            return;
        }

        Optional<UUID> projectId = diagramProjectResolver.get().resolveProjectId(diagramId);
        if (projectId.isEmpty()) {
            log.warn("No se pudo resolver el proyecto del diagrama {}; se rechaza el mensaje por falta de datos "
                    + "para validar el rol", diagramId);
            throw new ViewerNotAllowedException("No se pudo determinar el proyecto del diagrama " + diagramId);
        }

        Optional<String> role = projectRoleLookup.get().findRole(projectId.get(), userId);
        if (role.isPresent() && VIEWER_ROLE.equalsIgnoreCase(role.get())) {
            throw new ViewerNotAllowedException("El usuario " + userId
                    + " tiene rol VIEWER y no puede emitir mensajes de colaboracion en el diagrama " + diagramId);
        }
    }

    /**
     * Si {@code destination} coincide con alguno de los cuatro destinos protegidos
     * por rol ({@code /mutate}, {@code /lock}, {@code /presence/join}, {@code /cursor}),
     * devuelve el {@code diagramId} extraido de la ruta; en caso contrario, {@code null}.
     */
    private UUID extractGuardedDiagramId(String destination) {
        for (Pattern pattern : ROLE_GUARDED_DESTINATIONS) {
            Matcher matcher = pattern.matcher(destination);
            if (matcher.matches()) {
                try {
                    return UUID.fromString(matcher.group(1));
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private String firstNativeHeader(StompHeaderAccessor accessor, String name) {
        List<String> values = accessor.getNativeHeader(name);
        return (values == null || values.isEmpty()) ? null : values.get(0);
    }

    private static String colorFor(UUID userId) {
        int index = Math.floorMod(userId.hashCode(), COLOR_PALETTE.length);
        return COLOR_PALETTE[index];
    }

    /**
     * Principal cuyo {@code getName()} es el userId autenticado por JWT (String),
     * requerido para que {@code SimpMessagingTemplate.convertAndSendToUser} pueda
     * enrutar mensajes (p.ej. LOCK_DENIED) a la sesion correcta.
     */
    private record AuthenticatedPrincipal(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }
}
