package com.example.demo.collaboration.service;

import com.example.demo.collaboration.dto.LockMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Motor de soft-locks con TTL (seccion 6): exclusion mutua hibrida sin cola de
 * espera. Estado 100% efimero en memoria ({@link ConcurrentHashMap}), nunca
 * persistido en PostgreSQL.
 *
 * <p>Fallo rapido: si el recurso ya esta tomado, {@link #acquire} responde
 * inmediatamente con {@link LockMessage.LockDenied} dirigido solo al solicitante;
 * ningun cliente queda esperando en una cola.</p>
 *
 * <p>Expiracion doble: pasiva (una entrada vencida se descarta la proxima vez que
 * se consulta/solicita ese target) y activa (un {@link ScheduledExecutorService}
 * barre periodicamente el mapa completo y difunde {@code LOCK_RELEASED} para
 * cualquier lock vencido, incluso si nadie vuelve a pedirlo).</p>
 */
@Service
public class LockManagerService {

    private static final Logger log = LoggerFactory.getLogger(LockManagerService.class);

    public static final long DEFAULT_TTL_MILLIS = 5000L;
    private static final long SWEEP_INTERVAL_MILLIS = 1000L;

    private final Map<UUID, LockEntry> locksByTargetId = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private ScheduledExecutorService scheduler;

    public LockManagerService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    @PostConstruct
    void startExpirationSweeper() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "lock-ttl-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::sweepExpiredLocks, SWEEP_INTERVAL_MILLIS, SWEEP_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stopExpirationSweeper() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Intenta adquirir el lock de {@code targetId} para {@code userId}. Si el recurso
     * esta libre (o su lock previo ya expiro), lo concede con TTL de
     * {@value #DEFAULT_TTL_MILLIS} ms y difunde {@code LOCK_ACQUIRED} a la sala. Si
     * esta tomado por otro usuario y sigue vigente, responde solo al solicitante con
     * {@code LOCK_DENIED} (fallo rapido, sin cola de espera).
     */
    public void acquire(UUID diagramId, UUID targetId, UUID userId, String userName, String color) {
        long now = System.currentTimeMillis();
        LockEntry[] denialHolder = new LockEntry[1];

        LockEntry newEntry = locksByTargetId.compute(targetId, (id, existing) -> {
            if (existing != null && !existing.isExpired(now) && !existing.userId().equals(userId)) {
                denialHolder[0] = existing;
                return existing;
            }
            return new LockEntry(diagramId, userId, userName, color, now + DEFAULT_TTL_MILLIS);
        });

        if (denialHolder[0] != null) {
            LockEntry heldBy = denialHolder[0];
            long expiresInMs = Math.max(0, heldBy.expiresAtEpochMs() - now);
            sendToUser(userId, new LockMessage.LockDenied(targetId, heldBy.userId(), expiresInMs));
            return;
        }

        broadcastToTopic(diagramId, new LockMessage.LockAcquired(targetId, userId, userName, color, DEFAULT_TTL_MILLIS));
        log.debug("Lock {} adquirido por usuario {} en diagrama {}", targetId, userId, diagramId);
    }

    /**
     * Libera explicitamente un lock, solo si {@code userId} es su titular actual.
     * Difunde {@code LOCK_RELEASED} a la sala si tuvo efecto.
     */
    public boolean release(UUID targetId, UUID userId) {
        boolean[] released = new boolean[1];
        locksByTargetId.computeIfPresent(targetId, (id, entry) -> {
            if (entry.userId().equals(userId)) {
                released[0] = true;
                broadcastToTopic(entry.diagramId(), new LockMessage.LockReleased(targetId));
                return null;
            }
            return entry;
        });
        return released[0];
    }

    /**
     * Renueva el TTL de un lock existente (heartbeat del cliente cada 2000 ms).
     * No vuelve a difundir nada: solo extiende la vigencia.
     */
    public boolean renew(UUID targetId, UUID userId) {
        long now = System.currentTimeMillis();
        boolean[] renewed = new boolean[1];
        locksByTargetId.computeIfPresent(targetId, (id, entry) -> {
            if (!entry.isExpired(now) && entry.userId().equals(userId)) {
                renewed[0] = true;
                return entry.withExpiresAt(now + DEFAULT_TTL_MILLIS);
            }
            return entry;
        });
        return renewed[0];
    }

    /**
     * Indica si {@code targetId} esta actualmente bloqueado por {@code userId}
     * (lock vigente, no expirado). Usado por el controlador para validar operaciones
     * catalogadas como LOCK_REQUIRED antes de aplicarlas/difundirlas.
     */
    public boolean isHeldBy(UUID targetId, UUID userId) {
        LockEntry entry = locksByTargetId.get(targetId);
        return entry != null && !entry.isExpired(System.currentTimeMillis()) && entry.userId().equals(userId);
    }

    /**
     * Libera (sin verificar titularidad, ya que se invoca en desconexion) todos los
     * locks que posee {@code userId}, difundiendo {@code LOCK_RELEASED} para cada uno.
     * Invocado por el listener de desconexion de sesion STOMP.
     */
    public void releaseAllForUser(UUID userId) {
        locksByTargetId.forEach((targetId, entry) -> {
            if (entry.userId().equals(userId)) {
                locksByTargetId.computeIfPresent(targetId, (id, current) -> {
                    if (current.userId().equals(userId)) {
                        broadcastToTopic(current.diagramId(), new LockMessage.LockReleased(targetId));
                        return null;
                    }
                    return current;
                });
            }
        });
    }

    private void sweepExpiredLocks() {
        long now = System.currentTimeMillis();
        locksByTargetId.forEach((targetId, entry) -> {
            if (entry.isExpired(now)) {
                locksByTargetId.computeIfPresent(targetId, (id, current) -> {
                    if (current.isExpired(now)) {
                        broadcastToTopic(current.diagramId(), new LockMessage.LockReleased(targetId));
                        return null;
                    }
                    return current;
                });
            }
        });
    }

    private void broadcastToTopic(UUID diagramId, LockMessage message) {
        messagingTemplate.convertAndSend("/topic/diagrams/" + diagramId, message);
    }

    private void sendToUser(UUID userId, LockMessage message) {
        // NOTA DE INTEGRACION: se usa userId.toString() como identidad de destino de
        // convertAndSendToUser. Esto exige que el Principal de la sesion STOMP tenga
        // getName() == userId.toString(); StompChannelInterceptor lo establece de forma
        // provisional a partir de un header CONNECT hasta que exista autenticacion real.
        messagingTemplate.convertAndSendToUser(userId.toString(), "/queue/locks", message);
    }

    /**
     * Entrada de lock efimera (no persistida). Inmutable: renovar el TTL crea una
     * nueva instancia que reemplaza atomicamente la anterior en el mapa.
     */
    private record LockEntry(UUID diagramId, UUID userId, String userName, String color, long expiresAtEpochMs) {
        boolean isExpired(long nowEpochMs) {
            return expiresAtEpochMs <= nowEpochMs;
        }

        LockEntry withExpiresAt(long newExpiresAtEpochMs) {
            return new LockEntry(diagramId, userId, userName, color, newExpiresAtEpochMs);
        }
    }
}
