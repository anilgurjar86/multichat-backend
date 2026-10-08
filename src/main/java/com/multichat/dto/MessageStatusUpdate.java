package com.multichat.dto;

import java.util.List;

/** Pushed to the original sender when their messages become DELIVERED or READ. */
public record MessageStatusUpdate(List<String> messageIds, String status) {}
