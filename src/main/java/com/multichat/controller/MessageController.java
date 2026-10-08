package com.multichat.controller;

import com.multichat.dto.MessageDto;
import com.multichat.entity.User;
import com.multichat.repository.UserRepository;
import com.multichat.service.MessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final UserRepository userRepository;

    @GetMapping("/history/{otherUserId}")
    public ResponseEntity<List<MessageDto>> getHistory(@PathVariable String otherUserId) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();
        User currentUser = userRepository.findByUsername(currentUsername).orElseThrow();
        
        return ResponseEntity.ok(messageService.getHistory(currentUser.getId(), otherUserId));
    }

    @PutMapping("/{messageId}/read")
    public ResponseEntity<Void> markAsRead(@PathVariable String messageId) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        messageService.markAsRead(messageId, currentUsername);
        return ResponseEntity.ok().build();
    }

    /** REST alternative to the /app/chat.read STOMP message: mark the whole conversation as read. */
    @PutMapping("/conversation/{senderId}/read")
    public ResponseEntity<Void> markConversationAsRead(@PathVariable String senderId) {
        String currentUsername = SecurityContextHolder.getContext().getAuthentication().getName();

        messageService.markConversationRead(currentUsername, senderId);
        return ResponseEntity.ok().build();
    }
}
