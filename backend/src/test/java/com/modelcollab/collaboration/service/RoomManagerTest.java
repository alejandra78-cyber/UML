package com.modelcollab.collaboration.service;

import com.modelcollab.collaboration.dto.PresenceMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Test unitario de {@link RoomManager} (presencia y cursores en la sala STOMP
 * de cada diagrama). No levanta contexto Spring: {@link SimpMessagingTemplate}
 * se mockea con Mockito.
 */
class RoomManagerTest {

    private SimpMessagingTemplate messagingTemplate;
    private RoomManager roomManager;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        roomManager = new RoomManager(messagingTemplate);
    }

    @Test
    void join_registersMemberAndBindingAndBroadcastsJoined() {
        String sessionId = "session-1";
        UUID diagramId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        roomManager.join(sessionId, diagramId, userId, "Ana", "#ff0000");

        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId),
                eq(new PresenceMessage(PresenceMessage.Type.JOINED, diagramId, userId, "Ana", "#ff0000", null, null, null)));

        assertThat(roomManager.membersOf(diagramId))
                .containsExactly(new RoomManager.RoomMember(userId, "Ana", "#ff0000"));
        assertThat(roomManager.findMember(diagramId, userId))
                .contains(new RoomManager.RoomMember(userId, "Ana", "#ff0000"));
        assertThat(roomManager.bindingFor(sessionId))
                .contains(new RoomManager.SessionBinding(diagramId, userId));
    }

    @Test
    void leaveBySession_removesMemberAndBindingAndBroadcastsLeft() {
        String sessionId = "session-1";
        UUID diagramId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        roomManager.join(sessionId, diagramId, userId, "Ana", "#ff0000");

        Optional<RoomManager.SessionBinding> binding = roomManager.leaveBySession(sessionId);

        assertThat(binding).contains(new RoomManager.SessionBinding(diagramId, userId));
        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId),
                eq(new PresenceMessage(PresenceMessage.Type.LEFT, diagramId, userId, "Ana", "#ff0000", null, null, null)));

        assertThat(roomManager.membersOf(diagramId)).isEmpty();
        assertThat(roomManager.findMember(diagramId, userId)).isEmpty();
        assertThat(roomManager.bindingFor(sessionId)).isEmpty();
    }

    @Test
    void leaveBySession_unknownSession_returnsEmptyAndDoesNotBroadcast() {
        Optional<RoomManager.SessionBinding> binding = roomManager.leaveBySession("no-existe");

        assertThat(binding).isEmpty();
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void broadcastCursor_broadcastsCursorUpdate() {
        UUID diagramId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID selectedId = UUID.randomUUID();

        roomManager.broadcastCursor(diagramId, userId, "Ana", "#ff0000", 12.5, 34.5, selectedId);

        verify(messagingTemplate).convertAndSend(eq("/topic/diagrams/" + diagramId),
                eq(new PresenceMessage(PresenceMessage.Type.CURSOR_UPDATE, diagramId, userId, "Ana", "#ff0000",
                        12.5, 34.5, selectedId)));
    }

    @Test
    void membersOf_reflectsMultipleMembersInSameRoomIndependentlyOfOtherRooms() {
        UUID diagramId = UUID.randomUUID();
        UUID otherDiagramId = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();

        roomManager.join("s1", diagramId, userA, "Ana", "#111111");
        roomManager.join("s2", diagramId, userB, "Beto", "#222222");
        roomManager.join("s3", otherDiagramId, userA, "Ana", "#111111");

        assertThat(roomManager.membersOf(diagramId)).containsExactlyInAnyOrder(
                new RoomManager.RoomMember(userA, "Ana", "#111111"),
                new RoomManager.RoomMember(userB, "Beto", "#222222"));
        assertThat(roomManager.membersOf(otherDiagramId))
                .containsExactly(new RoomManager.RoomMember(userA, "Ana", "#111111"));
    }

    @Test
    void findMember_unknownUserOrRoom_returnsEmpty() {
        UUID diagramId = UUID.randomUUID();
        roomManager.join("s1", diagramId, UUID.randomUUID(), "Ana", "#111111");

        assertThat(roomManager.findMember(diagramId, UUID.randomUUID())).isEmpty();
        assertThat(roomManager.findMember(UUID.randomUUID(), UUID.randomUUID())).isEmpty();
    }

    @Test
    void leaveBySession_lastMemberOfRoom_removesRoomEntirely() {
        String sessionId = "session-1";
        UUID diagramId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        roomManager.join(sessionId, diagramId, userId, "Ana", "#ff0000");

        roomManager.leaveBySession(sessionId);

        assertThat(roomManager.membersOf(diagramId)).isEmpty();
    }
}
