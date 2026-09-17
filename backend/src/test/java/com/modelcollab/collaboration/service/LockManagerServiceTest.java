package com.modelcollab.collaboration.service;

import com.modelcollab.collaboration.dto.LockMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Test unitario de {@link LockManagerService} (motor de soft-locks con TTL,
 * seccion 6). No levanta contexto Spring: {@link SimpMessagingTemplate} se
 * mockea con Mockito y el servicio se instancia directamente.
 *
 * <p><b>Como se cubre la expiracion por TTL sin dormir 5+ segundos:</b>
 * {@code DEFAULT_TTL_MILLIS} esta hardcodeado en 5000 ms y no es inyectable,
 * asi que {@link #acquire_onExpiredLock_treatsResourceAsFreeAndGrantsToNewRequester()}
 * fuerza la expiracion via reflexion (reconstruye la {@code LockEntry} privada
 * del mapa interno con {@code expiresAtEpochMs = 0}) en vez de esperar el
 * reloj real. Esto ejercita exactamente la rama de decision de
 * {@code acquire} que trata un lock vencido como recurso libre.</p>
 *
 * <p>Gap menor, no bloqueante, que queda fuera de este test: el barrido de
 * expiracion activo ({@code sweepExpiredLocks}, disparado cada 1000 ms por el
 * {@code ScheduledExecutorService} interno) que difunde {@code LOCK_RELEASED}
 * incluso si nadie vuelve a pedir el recurso -- probarlo tal cual exigiria
 * dormir varios segundos o exponer el scheduler para inyectar un reloj de
 * prueba.</p>
 */
class LockManagerServiceTest {

    private SimpMessagingTemplate messagingTemplate;
    private LockManagerService lockManagerService;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        lockManagerService = new LockManagerService(messagingTemplate);
    }

    @Test
    void acquire_onFreeTarget_broadcastsLockAcquiredToRoom() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        lockManagerService.acquire(diagramId, targetId, userId, "Ana", "#ff0000");

        verify(messagingTemplate).convertAndSend(
                eq("/topic/diagrams/" + diagramId),
                eq(new LockMessage.LockAcquired(targetId, userId, "Ana", "#ff0000", LockManagerService.DEFAULT_TTL_MILLIS)));
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        assertThat(lockManagerService.isHeldBy(targetId, userId)).isTrue();
    }

    @Test
    void acquire_onLockAlreadyHeldByAnotherUser_deniesOnlyToRequesterAndLeavesExistingLockIntact() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID requester = UUID.randomUUID();

        lockManagerService.acquire(diagramId, targetId, holder, "Holder", "#111111");
        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId), any(LockMessage.LockAcquired.class));

        lockManagerService.acquire(diagramId, targetId, requester, "Requester", "#222222");

        verify(messagingTemplate).convertAndSendToUser(
                eq(requester.toString()), eq("/queue/locks"), any(LockMessage.LockDenied.class));
        // El lock existente no debe reasignarse ni volver a difundirse.
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/diagrams/" + diagramId), any(LockMessage.LockAcquired.class));
        assertThat(lockManagerService.isHeldBy(targetId, holder)).isTrue();
        assertThat(lockManagerService.isHeldBy(targetId, requester)).isFalse();
    }

    @Test
    void acquire_sameUserAlreadyHoldingLock_isTreatedAsReacquireNotDenial() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        lockManagerService.acquire(diagramId, targetId, userId, "Ana", "#ff0000");
        lockManagerService.acquire(diagramId, targetId, userId, "Ana", "#ff0000");

        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        assertThat(lockManagerService.isHeldBy(targetId, userId)).isTrue();
    }

    @Test
    void acquire_onExpiredLock_treatsResourceAsFreeAndGrantsToNewRequester() throws Exception {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID previousHolder = UUID.randomUUID();
        UUID newRequester = UUID.randomUUID();

        lockManagerService.acquire(diagramId, targetId, previousHolder, "Viejo", "#111111");
        forceLockExpiry(targetId);

        lockManagerService.acquire(diagramId, targetId, newRequester, "Nuevo", "#222222");

        assertThat(lockManagerService.isHeldBy(targetId, newRequester)).isTrue();
        assertThat(lockManagerService.isHeldBy(targetId, previousHolder)).isFalse();
        verify(messagingTemplate, never()).convertAndSendToUser(any(), any(), any());
        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId),
                eq(new LockMessage.LockAcquired(targetId, newRequester, "Nuevo", "#222222", LockManagerService.DEFAULT_TTL_MILLIS)));
    }

    @Test
    void release_byCurrentHolder_removesLockAndBroadcastsLockReleased() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        lockManagerService.acquire(diagramId, targetId, userId, "Ana", "#ff0000");

        boolean released = lockManagerService.release(targetId, userId);

        assertThat(released).isTrue();
        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId), eq(new LockMessage.LockReleased(targetId)));
        assertThat(lockManagerService.isHeldBy(targetId, userId)).isFalse();
    }

    @Test
    void release_byNonHolder_hasNoEffectAndDoesNotBroadcast() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID impostor = UUID.randomUUID();
        lockManagerService.acquire(diagramId, targetId, holder, "Holder", "#111111");

        boolean released = lockManagerService.release(targetId, impostor);

        assertThat(released).isFalse();
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/diagrams/" + diagramId), any(LockMessage.LockAcquired.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/diagrams/" + diagramId), any(LockMessage.LockReleased.class));
        assertThat(lockManagerService.isHeldBy(targetId, holder)).isTrue();
    }

    @Test
    void release_onUnknownTarget_hasNoEffect() {
        boolean released = lockManagerService.release(UUID.randomUUID(), UUID.randomUUID());

        assertThat(released).isFalse();
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void renew_byCurrentHolder_extendsLockAndSucceeds() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        lockManagerService.acquire(diagramId, targetId, userId, "Ana", "#ff0000");

        boolean renewed = lockManagerService.renew(targetId, userId);

        assertThat(renewed).isTrue();
        assertThat(lockManagerService.isHeldBy(targetId, userId)).isTrue();
        // Renovar no vuelve a difundir nada (solo extiende el TTL).
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/diagrams/" + diagramId), any(LockMessage.LockAcquired.class));
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void renew_byNonHolder_failsAndDoesNotAlterLock() {
        UUID diagramId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        UUID impostor = UUID.randomUUID();
        lockManagerService.acquire(diagramId, targetId, holder, "Holder", "#111111");

        boolean renewed = lockManagerService.renew(targetId, impostor);

        assertThat(renewed).isFalse();
        assertThat(lockManagerService.isHeldBy(targetId, holder)).isTrue();
    }

    @Test
    void renew_onUnknownTarget_fails() {
        boolean renewed = lockManagerService.renew(UUID.randomUUID(), UUID.randomUUID());

        assertThat(renewed).isFalse();
    }

    @Test
    void releaseAllForUser_releasesOnlyLocksOwnedByThatUserAndBroadcastsEachOne() {
        UUID diagramId = UUID.randomUUID();
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        UUID targetC = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();

        lockManagerService.acquire(diagramId, targetA, userId, "Ana", "#ff0000");
        lockManagerService.acquire(diagramId, targetB, userId, "Ana", "#ff0000");
        lockManagerService.acquire(diagramId, targetC, otherUser, "Otro", "#00ff00");

        lockManagerService.releaseAllForUser(userId);

        assertThat(lockManagerService.isHeldBy(targetA, userId)).isFalse();
        assertThat(lockManagerService.isHeldBy(targetB, userId)).isFalse();
        assertThat(lockManagerService.isHeldBy(targetC, otherUser)).isTrue();

        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId), eq(new LockMessage.LockReleased(targetA)));
        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId), eq(new LockMessage.LockReleased(targetB)));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/diagrams/" + diagramId), eq(new LockMessage.LockReleased(targetC)));
    }

    /**
     * Reescribe, via reflexion, la {@code LockEntry} privada guardada para
     * {@code targetId} dentro del mapa interno {@code locksByTargetId},
     * preservando todos sus campos salvo {@code expiresAtEpochMs} (forzado a
     * 0, es decir "vencido desde el epoch"). Evita depender del reloj real
     * para ejercitar la rama de expiracion de {@code acquire}.
     */
    @SuppressWarnings("unchecked")
    private void forceLockExpiry(UUID targetId) throws ReflectiveOperationException {
        Field mapField = LockManagerService.class.getDeclaredField("locksByTargetId");
        mapField.setAccessible(true);
        Map<UUID, Object> locks = (Map<UUID, Object>) mapField.get(lockManagerService);

        Object currentEntry = locks.get(targetId);
        Class<?> entryClass = currentEntry.getClass();

        Object diagramId = invokeRecordAccessor(currentEntry, "diagramId");
        Object userId = invokeRecordAccessor(currentEntry, "userId");
        Object userName = invokeRecordAccessor(currentEntry, "userName");
        Object color = invokeRecordAccessor(currentEntry, "color");

        Constructor<?> constructor = entryClass.getDeclaredConstructor(
                UUID.class, UUID.class, String.class, String.class, long.class);
        constructor.setAccessible(true);
        Object expiredEntry = constructor.newInstance(diagramId, userId, userName, color, 0L);

        locks.put(targetId, expiredEntry);
    }

    private Object invokeRecordAccessor(Object target, String componentName) throws ReflectiveOperationException {
        Method accessor = target.getClass().getDeclaredMethod(componentName);
        accessor.setAccessible(true);
        return accessor.invoke(target);
    }
}
