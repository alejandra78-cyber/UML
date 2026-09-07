package com.example.demo.collaboration.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LwwConflictResolverTest {

    private final LwwConflictResolver resolver = new LwwConflictResolver();

    @Test
    void resolve_appliesFirstUpdateForNewTarget() {
        UUID targetId = UUID.randomUUID();

        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetId, 100L, "primer-valor");

        assertThat(result.applied()).isTrue();
        assertThat(result.effectiveValue()).isEqualTo("primer-valor");
    }

    @Test
    void resolve_acceptsStrictlyNewerTimestamp() {
        UUID targetId = UUID.randomUUID();
        resolver.resolve(targetId, 100L, "viejo");

        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetId, 200L, "nuevo");

        assertThat(result.applied()).isTrue();
        assertThat(result.effectiveValue()).isEqualTo("nuevo");
    }

    @Test
    void resolve_rejectsOlderTimestampAfterNewerAlreadyAccepted() {
        UUID targetId = UUID.randomUUID();
        resolver.resolve(targetId, 200L, "reciente");

        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetId, 100L, "desordenado");

        assertThat(result.applied()).isFalse();
    }

    @Test
    void resolve_rejectedUpdateDoesNotOverwriteLastAcceptedState() {
        UUID targetId = UUID.randomUUID();
        resolver.resolve(targetId, 200L, "reciente");
        resolver.resolve(targetId, 100L, "desordenado-1");

        // Una segunda actualizacion desordenada tras la primera rechazada tambien debe
        // rechazarse: el estado interno no debio quedar corrompido por el intento anterior.
        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetId, 150L, "desordenado-2");

        assertThat(result.applied()).isFalse();
    }

    @Test
    void resolve_acceptsEqualTimestamp_lastWriteAtSameInstantWins() {
        UUID targetId = UUID.randomUUID();
        resolver.resolve(targetId, 100L, "primero");

        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetId, 100L, "segundo-mismo-instante");

        assertThat(result.applied()).isTrue();
    }

    @Test
    void resolve_tracksDifferentTargetsIndependently() {
        UUID targetA = UUID.randomUUID();
        UUID targetB = UUID.randomUUID();
        resolver.resolve(targetA, 500L, "a-reciente");

        // Un timestamp bajo para un target distinto no debe verse afectado por el
        // estado ya aceptado de otro target.
        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetB, 10L, "b-primero");

        assertThat(result.applied()).isTrue();
    }

    @Test
    void forget_removesState_allowingAnyTimestampAgain() {
        UUID targetId = UUID.randomUUID();
        resolver.resolve(targetId, 500L, "reciente");

        resolver.forget(targetId);

        LwwConflictResolver.LwwResult<String> result = resolver.resolve(targetId, 10L, "post-forget");
        assertThat(result.applied()).isTrue();
    }
}
