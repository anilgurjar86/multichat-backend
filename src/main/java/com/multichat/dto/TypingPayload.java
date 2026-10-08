package com.multichat.dto;

public record TypingPayload(String senderId, String receiverId, boolean typing) {}
