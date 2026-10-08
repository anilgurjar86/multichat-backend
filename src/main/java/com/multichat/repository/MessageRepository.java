package com.multichat.repository;

import com.multichat.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, String> {
    @Query("SELECT m FROM Message m WHERE (m.sender.id = :uid1 AND m.receiver.id = :uid2) OR (m.sender.id = :uid2 AND m.receiver.id = :uid1) ORDER BY m.timestamp ASC")
    List<Message> findConversation(@Param("uid1") String uid1, @Param("uid2") String uid2);

    // Messages waiting for a receiver who was offline
    List<Message> findByReceiver_IdAndStatus(String receiverId, Message.MessageStatus status);

    // Unread messages in one direction of a conversation
    List<Message> findBySender_IdAndReceiver_IdAndStatusNot(String senderId, String receiverId, Message.MessageStatus status);
}
