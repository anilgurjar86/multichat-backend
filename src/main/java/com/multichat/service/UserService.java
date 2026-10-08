package com.multichat.service;

import com.multichat.dto.UserDto;
import com.multichat.entity.User;
import com.multichat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

    public List<UserDto> getAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public UserDto getUserById(String id) {
        return userRepository.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new RuntimeException("User not found"));
    }

    @Transactional
    public Optional<UserDto> setOnlineStatus(String username, boolean online) {
        return userRepository.findByUsername(username).map(user -> {
            user.setOnline(online);
            return toDto(userRepository.save(user));
        });
    }

    /** Nobody has an open WebSocket right after startup, so clear stale flags left by a crash/restart. */
    @Transactional
    public int markAllOffline() {
        return userRepository.markAllOffline();
    }

    private UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getUsername(), u.getFirstName(), u.getLastName(), u.isOnline());
    }
}
