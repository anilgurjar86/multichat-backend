package com.multichat.dto;

/** Sent by a client to say "I have read every message senderId sent me". */
public record ReadReceiptPayload(String senderId) {}
