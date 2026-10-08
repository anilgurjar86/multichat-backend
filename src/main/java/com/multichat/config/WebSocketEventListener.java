package com.multichat.config;

import com.multichat.dto.PresencePayload;
import com.multichat.service.MessageService;
import com.multichat.service.PresenceService;
import com.multichat.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketEventListener {

    public static final String PRESENCE_TOPIC = "/topic/presence";

    private final UserService userService;
    private final PresenceService presenceService;
    private final MessageService messageService;
    private final SimpMessageSendingOperations messagingTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void resetPresenceOnStartup() {
        int reset = userService.markAllOffline();
        log.info("Reset {} stale online flag(s) on startup", reset);
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        Principal user = event.getUser();
        if (user == null) {
            return;
        }
        String username = user.getName();
        String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
        log.info("User connected via WebSocket: {} (session {})", username, sessionId);

        if (presenceService.sessionConnected(username, sessionId)) {
            userService.setOnlineStatus(username, true).ifPresent(dto ->
                    messagingTemplate.convertAndSend(PRESENCE_TOPIC,
                            new PresencePayload(dto.id(), dto.username(), true)));
        }
        // Messages stored while the user was offline are now delivered: ✓ -> ✓✓ for their senders
        messageService.markPendingAsDelivered(username);
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        Principal user = event.getUser();
        if (user == null) {
            return;
        }
        String username = user.getName();
        log.info("User disconnected from WebSocket: {} (session {})", username, event.getSessionId());

        if (presenceService.sessionDisconnected(username, event.getSessionId())) {
            userService.setOnlineStatus(username, false).ifPresent(dto ->
                    messagingTemplate.convertAndSend(PRESENCE_TOPIC,
                            new PresencePayload(dto.id(), dto.username(), false)));
        }
    }
}
