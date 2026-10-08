package com.multichat.dto;

public record MessageDto(String id, String senderId, String receiverId, String content, String timestamp, String status) {}
