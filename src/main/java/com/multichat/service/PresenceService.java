package com.multichat.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of open WebSocket sessions per user.
 * A user is online while at least one session (browser tab) is connected.
 */
@Service
public class PresenceService {

    private final Map<String, Set<String>> sessionsByUsername = new ConcurrentHashMap<>();

    /** @return true if this is the user's first session, i.e. they just came online */
    public boolean sessionConnected(String username, String sessionId) {
        boolean[] cameOnline = {false};
        sessionsByUsername.compute(username, (u, sessions) -> {
            if (sessions == null) {
                sessions = ConcurrentHashMap.newKeySet();
            }
            cameOnline[0] = sessions.isEmpty();
            sessions.add(sessionId);
            return sessions;
        });
        return cameOnline[0];
    }

    /** @return true if this was the user's last session, i.e. they just went offline */
    public boolean sessionDisconnected(String username, String sessionId) {
        boolean[] wentOffline = {false};
        sessionsByUsername.computeIfPresent(username, (u, sessions) -> {
            // SessionDisconnectEvent may fire more than once per session, so only react to a real removal
            if (sessions.remove(sessionId) && sessions.isEmpty()) {
                wentOffline[0] = true;
            }
            return sessions.isEmpty() ? null : sessions;
        });
        return wentOffline[0];
    }

    public boolean isOnline(String username) {
        return sessionsByUsername.containsKey(username);
    }
}
