package com.multichat.controller;

import com.multichat.dto.ReadReceiptPayload;
import com.multichat.dto.SendMessageRequest;
import com.multichat.dto.TypingPayload;
import com.multichat.entity.User;
import com.multichat.repository.UserRepository;
import com.multichat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final UserRepository userRepository;

    @MessageMapping("/chat.send")
    public void sendMessage(SendMessageRequest request, Principal principal) {
        log.debug("Received chat message from {}", principal.getName());
        // Persists and pushes to /user/queue/messages of both receiver and sender
        messageService.sendMessage(principal.getName(), request);
    }

    @MessageMapping("/chat.typing")
    public void typing(TypingPayload payload, Principal principal) {
        User sender = userRepository.findByUsername(principal.getName()).orElseThrow();
        userRepository.findById(payload.receiverId()).ifPresent(receiver ->
                messagingTemplate.convertAndSendToUser(
                        receiver.getUsername(),
                        "/queue/typing",
                        // senderId comes from the authenticated principal, never from the client
                        new TypingPayload(sender.getId(), receiver.getId(), payload.typing())
                ));
    }

    @MessageMapping("/chat.read")
    public void readReceipt(ReadReceiptPayload payload, Principal principal) {
        // Marks all messages from payload.senderId() as READ and notifies that sender (blue ✓✓)
        messageService.markConversationRead(principal.getName(), payload.senderId());
    }
}
