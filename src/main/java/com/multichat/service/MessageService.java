package com.multichat.service;

import com.multichat.dto.MessageDto;
import com.multichat.dto.MessageStatusUpdate;
import com.multichat.dto.SendMessageRequest;
import com.multichat.entity.Message;
import com.multichat.entity.User;
import com.multichat.repository.MessageRepository;
import com.multichat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MessageService {

    public static final String MESSAGES_QUEUE = "/queue/messages";
    public static final String RECEIPTS_QUEUE = "/queue/receipts";

    private final MessageRepository messageRepository;
    private final UserRepository userRepository;
    private final PresenceService presenceService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Persists the message (so offline receivers get it on reconnect) and pushes it live to
     * both parties. Status is DELIVERED (✓✓) if the receiver is connected right now, else SENT (✓).
     */
    @Transactional
    public MessageDto sendMessage(String senderUsername, SendMessageRequest req) {
        if (req.content() == null || req.content().isBlank()) {
            throw new IllegalArgumentException("Message content is empty");
        }
        User sender = userRepository.findByUsername(senderUsername)
                .orElseThrow(() -> new RuntimeException("Sender not found"));
        User receiver = userRepository.findById(req.receiverId())
                .orElseThrow(() -> new RuntimeException("Receiver not found"));

        Message.MessageStatus status = presenceService.isOnline(receiver.getUsername())
                ? Message.MessageStatus.DELIVERED
                : Message.MessageStatus.SENT;

        Message message = Message.builder()
                .sender(sender)
                .receiver(receiver)
                .content(req.content().trim())
                .status(status)
                .build();

        MessageDto dto = toDto(messageRepository.save(message));

        // User destinations are keyed by principal name, i.e. the USERNAME (not the user id)
        afterCommit(() -> {
            messagingTemplate.convertAndSendToUser(receiver.getUsername(), MESSAGES_QUEUE, dto);
            if (!receiver.getUsername().equals(sender.getUsername())) {
                // Echo to the sender so all of their open tabs show the message with its real id/status
                messagingTemplate.convertAndSendToUser(sender.getUsername(), MESSAGES_QUEUE, dto);
            }
        });
        return dto;
    }

    @Transactional(readOnly = true)
    public List<MessageDto> getHistory(String userId1, String userId2) {
        return messageRepository.findConversation(userId1, userId2).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /** Reader has opened the conversation with senderId: mark everything from senderId as READ (blue ✓✓). */
    @Transactional
    public void markConversationRead(String readerUsername, String senderId) {
        User reader = userRepository.findByUsername(readerUsername).orElseThrow();
        List<Message> unread = messageRepository.findBySender_IdAndReceiver_IdAndStatusNot(
                senderId, reader.getId(), Message.MessageStatus.READ);
        if (unread.isEmpty()) {
            return;
        }
        unread.forEach(m -> m.setStatus(Message.MessageStatus.READ));
        messageRepository.saveAll(unread);

        String senderUsername = unread.get(0).getSender().getUsername();
        List<String> ids = unread.stream().map(Message::getId).toList();
        afterCommit(() -> notifyStatus(senderUsername, ids, Message.MessageStatus.READ));
    }

    @Transactional
    public void markAsRead(String messageId, String readerUsername) {
        messageRepository.findById(messageId).ifPresent(message -> {
            if (message.getReceiver().getUsername().equals(readerUsername)
                    && message.getStatus() != Message.MessageStatus.READ) {
                message.setStatus(Message.MessageStatus.READ);
                messageRepository.save(message);
                String senderUsername = message.getSender().getUsername();
                afterCommit(() -> notifyStatus(senderUsername, List.of(messageId), Message.MessageStatus.READ));
            }
        });
    }

    /** Called when a user comes online: every message that waited for them (✓) becomes DELIVERED (✓✓). */
    @Transactional
    public void markPendingAsDelivered(String receiverUsername) {
        User receiver = userRepository.findByUsername(receiverUsername).orElse(null);
        if (receiver == null) {
            return;
        }
        List<Message> pending = messageRepository.findByReceiver_IdAndStatus(
                receiver.getId(), Message.MessageStatus.SENT);
        if (pending.isEmpty()) {
            return;
        }
        pending.forEach(m -> m.setStatus(Message.MessageStatus.DELIVERED));
        messageRepository.saveAll(pending);

        Map<String, List<String>> idsBySender = pending.stream().collect(Collectors.groupingBy(
                m -> m.getSender().getUsername(),
                Collectors.mapping(Message::getId, Collectors.toList())));
        afterCommit(() -> idsBySender.forEach(
                (senderUsername, ids) -> notifyStatus(senderUsername, ids, Message.MessageStatus.DELIVERED)));
    }

    public MessageDto toDto(Message m) {
        return new MessageDto(
                m.getId(),
                m.getSender().getId(),
                m.getReceiver().getId(),
                m.getContent(),
                m.getTimestamp().toString(),
                m.getStatus().name()
        );
    }

    private void notifyStatus(String senderUsername, List<String> messageIds, Message.MessageStatus status) {
        messagingTemplate.convertAndSendToUser(
                senderUsername, RECEIPTS_QUEUE, new MessageStatusUpdate(messageIds, status.name()));
    }

    private void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
