# Multichat Backend: Complete Flow Documentation

Spring Boot 3.3.4 (Java 21) backend for a one-to-one real-time chat app. The Angular client is expected at `http://localhost:4200`.

| Concern | Technology |
|---|---|
| REST API | Spring Web (`/api/**`) |
| Real-time | WebSocket + STOMP, in-memory simple broker (`/ws`) |
| Auth | Spring Security + JWT (jjwt 0.12.3), BCrypt passwords, stateless sessions |
| Persistence | Spring Data JPA / Hibernate on PostgreSQL (`localhost:5433/multichat`, `ddl-auto=update`) |
| Boilerplate | Lombok, Java records for DTOs |

---

## 1. Architecture overview

```
                    Angular client (localhost:4200)
                 │                                 │
         HTTP (REST + JWT)                 WebSocket /ws (STOMP)
                 │                                 │
   ┌─────────────▼────────────┐     ┌──────────────▼──────────────────┐
   │ CorsConfig               │     │ WebSocketConfig                  │
   │ SecurityConfig           │     │  └ ChannelInterceptor (CONNECT   │
   │  └ JwtAuthFilter         │     │     → validates JWT "token" hdr) │
   └─────────────┬────────────┘     └──────────────┬──────────────────┘
                 │                                 │
   ┌─────────────▼────────────┐     ┌──────────────▼──────────────────┐
   │ AuthController           │     │ ChatController (@MessageMapping) │
   │ UserController           │     │ WebSocketEventListener (events)  │
   │ MessageController        │     └──────────────┬──────────────────┘
   └─────────────┬────────────┘                    │
                 └───────────────┬─────────────────┘
                 ┌───────────────▼──────────────────────────────┐
                 │ Services: AuthService, JwtService,           │
                 │ UserService, MessageService, PresenceService │
                 │ UserDetailsServiceImpl                       │
                 └───────────────┬──────────────────────────────┘
                 ┌───────────────▼──────────────┐   ┌─────────────────────────┐
                 │ UserRepository               │   │ SimpMessagingTemplate   │
                 │ MessageRepository (JPA)      │   │ → simple broker pushes  │
                 └───────────────┬──────────────┘   │ /topic/*, /user/queue/* │
                          PostgreSQL                └─────────────────────────┘
```

---

## 2. Class reference: what each class does

### 2.1 Bootstrap & configuration (`com.multichat`, `com.multichat.config`)

| Class | Responsibility |
|---|---|
| `MultichatApplication` | `main()` entry point; `@SpringBootApplication` triggers component scan and auto-config. |
| `CorsConfig` | `corsConfigurationSource()` bean: allows origin `http://localhost:4200`, methods GET/POST/PUT/PATCH/DELETE/OPTIONS, all headers, credentials. |
| `SecurityConfig` | `securityFilterChain()`: disables CSRF, applies CORS, permits `/api/auth/**` and `/ws/**`, requires authentication for everything else, uses STATELESS sessions, and registers `JwtAuthFilter` before `UsernamePasswordAuthenticationFilter`. Also defines the `AuthenticationProvider` (DAO + BCrypt), `AuthenticationManager` and `PasswordEncoder` beans. |
| `WebSocketConfig` | `registerStompEndpoints()`: native WS endpoint `/ws`. `configureMessageBroker()`: simple broker on `/topic` and `/queue`, app prefix `/app`, user prefix `/user`. `configureClientInboundChannel()`: interceptor that authenticates the STOMP **CONNECT** frame using the `token` native header. |
| `WebSocketEventListener` | Listens to `ApplicationReadyEvent` (reset all users offline), `SessionConnectedEvent` (presence online + deliver pending messages) and `SessionDisconnectEvent` (presence offline). |

### 2.2 Security (`com.multichat.security`)

| Class | Responsibility |
|---|---|
| `JwtAuthFilter` | `OncePerRequestFilter`. Reads `Authorization: Bearer <jwt>`, extracts the username, loads the user, validates the token, and puts an `Authentication` into `SecurityContextHolder`. |
| `UserDetailsServiceImpl` | `loadUserByUsername()`: loads the `User` entity via `UserRepository` and wraps it as Spring Security `UserDetails` (no roles). Throws `UsernameNotFoundException`. |

### 2.3 Controllers (`com.multichat.controller`)

| Class | Type | Endpoints |
|---|---|---|
| `AuthController` | REST | `POST /api/auth/register`, `POST /api/auth/login` |
| `UserController` | REST | `GET /api/users`, `GET /api/users/{id}` |
| `MessageController` | REST | `GET /api/messages/history/{otherUserId}`, `PUT /api/messages/{messageId}/read`, `PUT /api/messages/conversation/{senderId}/read` |
| `ChatController` | STOMP | `/app/chat.send`, `/app/chat.typing`, `/app/chat.read` |

### 2.4 Services (`com.multichat.service`)

| Class | Responsibility |
|---|---|
| `AuthService` | `register()`: checks for a duplicate username, BCrypt-hashes the password, saves the user, and returns a JWT plus the `UserDto`. `login()`: authenticates through the `AuthenticationManager` and returns a JWT plus the `UserDto`. |
| `JwtService` | `generateToken()` (subject = username, 24h expiry, HMAC key from `app.jwt.secret`), `extractUsername()`, `isTokenValid()` (username matches and not expired). |
| `UserService` | `getAllUsers()`, `getUserById()`, `setOnlineStatus()` (persists the `online` flag), `markAllOffline()`. |
| `PresenceService` | **In-memory** `Map<username, Set<sessionId>>`. A user is online while they have at least one open WS session (browser tab). `sessionConnected()` returns true on the first session and `sessionDisconnected()` returns true on the last one. |
| `MessageService` | Core chat logic: `sendMessage()`, `getHistory()`, `markConversationRead()`, `markAsRead()`, `markPendingAsDelivered()`. Pushes STOMP messages **after the DB transaction commits** (`afterCommit()`). |

### 2.5 Persistence (`com.multichat.entity`, `com.multichat.repository`)

| Class | Responsibility |
|---|---|
| `User` (table `users`) | `id` (UUID), `username` (unique), `password` (BCrypt), `firstName`, `lastName`, `online`, `createdAt`. |
| `Message` (table `messages`) | `id` (UUID), `sender` → users, `receiver` → users (both LAZY), `content` (TEXT), `status` enum `SENT / DELIVERED / READ` (default SENT), `timestamp`. |
| `UserRepository` | `findByUsername`, `existsByUsername`, `markAllOffline()` (bulk JPQL update). |
| `MessageRepository` | `findConversation(uid1, uid2)` (both directions, ordered by timestamp), `findByReceiver_IdAndStatus` (pending messages), `findBySender_IdAndReceiver_IdAndStatusNot` (unread in one direction). |

### 2.6 DTOs (`com.multichat.dto`, all Java records)

| DTO | Fields | Used for |
|---|---|---|
| `RegisterRequest` | username, password, firstName, lastName | register body |
| `LoginRequest` | username, password | login body |
| `AuthResponse` | token, user | register/login response |
| `UserDto` | id, username, firstName, lastName, online | user lists (no password) |
| `SendMessageRequest` | receiverId, content | `/app/chat.send` payload |
| `MessageDto` | id, senderId, receiverId, content, timestamp, status | history and `/user/queue/messages` |
| `MessageStatusUpdate` | messageIds, status | `/user/queue/receipts` (✓✓ / blue ✓✓) |
| `TypingPayload` | senderId, receiverId, typing | `/app/chat.typing` and `/user/queue/typing` |
| `ReadReceiptPayload` | senderId | `/app/chat.read` payload |
| `PresencePayload` | userId, username, online | `/topic/presence` broadcast |

### 2.7 Exceptions (`com.multichat.exception`)

| Class | Responsibility |
|---|---|
| `DuplicateUsernameException` | Thrown by `AuthService.register()`. |
| `GlobalExceptionHandler` | `@RestControllerAdvice` (REST only): Duplicate → **409**, `UsernameNotFoundException` → **401**, any other `Exception` → **500** `"An error occurred: ..."`. |

---

## 3. Step-by-step flows

### Flow 0: Application startup

1. `MultichatApplication.main()` starts Spring Boot.
2. Beans get created: `CorsConfig`, `SecurityConfig` (filter chain + `JwtAuthFilter`), `WebSocketConfig` (STOMP endpoint `/ws`, broker, CONNECT interceptor).
3. Hibernate (`ddl-auto=update`) creates or updates the `users` and `messages` tables.
4. When the app is ready, `WebSocketEventListener.resetPresenceOnStartup()` → `UserService.markAllOffline()` → `UserRepository.markAllOffline()` sets `online=false` for every user. Nobody can be connected right after a restart, so this clears stale flags. `PresenceService` starts empty.

---

### Flow 1: Register (`POST /api/auth/register`)

```
Client → JwtAuthFilter (no token, passes through) → SecurityConfig permitAll(/api/auth/**)
       → AuthController.register() → AuthService.register() → DB → 201 {token, user}
```

1. The client sends `{username, password, firstName, lastName}`.
2. `JwtAuthFilter.doFilterInternal()` finds no `Bearer` header and passes the request on. The path is in `permitAll`.
3. `AuthController.register()` calls `AuthService.register(request)`.
4. `AuthService.register()`:
   1. `userRepository.existsByUsername()`: if true, throws `DuplicateUsernameException`, which `GlobalExceptionHandler` turns into **409 Conflict**.
   2. Builds a `User` with the password hashed by `passwordEncoder.encode()` (BCrypt) and `online=false`.
   3. `userRepository.save(user)` inserts it into `users`.
   4. `userDetailsService.loadUserByUsername()` → `jwtService.generateToken()` creates a JWT (sub=username, exp=now+24h, HS256).
   5. Returns `AuthResponse(token, UserDto)`.
5. The controller responds with **201 Created**.

---

### Flow 2: Login (`POST /api/auth/login`)

1. The client sends `{username, password}`.
2. The request passes the filter (public path) and reaches `AuthController.login()` → `AuthService.login()`.
3. `authenticationManager.authenticate(UsernamePasswordAuthenticationToken)`:
   - `DaoAuthenticationProvider` (from `SecurityConfig`) calls `UserDetailsServiceImpl.loadUserByUsername()`, then checks the password with BCrypt.
   - On failure it throws `BadCredentialsException`.
4. `userRepository.findByUsername()` loads the entity.
5. **The online flag is NOT set here.** That happens later, when the WebSocket connects (Flow 4).
6. A JWT is generated and returned as `AuthResponse` with **200 OK**.
7. The client stores the token and uses it for (a) REST calls in the `Authorization: Bearer <token>` header and (b) the STOMP CONNECT `token` header.

---

### Flow 3: Any authenticated REST request (JWT filter)

1. The request arrives, for example `GET /api/users` with `Authorization: Bearer eyJ...`.
2. CORS is checked by `CorsConfig`.
3. `JwtAuthFilter.doFilterInternal()`:
   1. Strips `"Bearer "` and calls `jwtService.extractUsername(jwt)`. This verifies the signature with the HMAC key and parses the claims.
   2. If the username is not null and no auth is set yet, it calls `userDetailsService.loadUserByUsername()`.
   3. If `jwtService.isTokenValid()` passes (subject matches and not expired), it creates a `UsernamePasswordAuthenticationToken` and stores it in `SecurityContextHolder`.
4. The `authorizeHttpRequests` rule `anyRequest().authenticated()` lets the request through, or returns **403** if no auth was set.
5. The controller runs and reads the current user via `SecurityContextHolder.getContext().getAuthentication().getName()` (the username).

#### 3a. List users: `GET /api/users`
`UserController.getAllUsers()` → `UserService.getAllUsers()` → `userRepository.findAll()` → mapped to `UserDto` (includes the `online` flag, excludes the password). The list includes the caller.

#### 3b. One user: `GET /api/users/{id}`
`UserController.getUserById()` → `UserService.getUserById()`. If not found, it throws `RuntimeException`, which becomes **500**.

#### 3c. Chat history: `GET /api/messages/history/{otherUserId}`
1. `MessageController.getHistory()` resolves the current user from the security context and loads them by username.
2. `MessageService.getHistory(currentUserId, otherUserId)` → `MessageRepository.findConversation()` gets messages in both directions, ordered by timestamp ascending.
3. Each message is mapped by `toDto()` to `MessageDto` and returned as JSON.

---

### Flow 4: WebSocket connect & presence online

```
Client ──CONNECT (header token=<jwt>)──► /ws
   WebSocketConfig interceptor.preSend()  → validate JWT → accessor.setUser(principal)
   ◄──CONNECTED──
   SessionConnectedEvent → WebSocketEventListener.handleWebSocketConnectListener()
        → PresenceService.sessionConnected()  (first tab?)
              yes → UserService.setOnlineStatus(true) → broadcast /topic/presence {online:true}
        → MessageService.markPendingAsDelivered()  → /user/queue/receipts to senders (✓✓)
```

1. The client opens a native WebSocket to `ws://localhost:8080/ws` (`/ws/**` is public at the HTTP level).
2. The client sends a STOMP `CONNECT` frame with the native header `token: <jwt>`.
3. `WebSocketConfig` → `ChannelInterceptor.preSend()`:
   1. Only acts on the `CONNECT` command.
   2. A missing token throws `MessageDeliveryException("Missing auth token")` and the client receives an ERROR frame.
   3. `jwtService.extractUsername()` → `userDetailsService.loadUserByUsername()` → `jwtService.isTokenValid()`.
   4. If valid, `accessor.setUser(UsernamePasswordAuthenticationToken)`. **The principal name (the username) is what all `/user/...` routing uses from now on.**
   5. Any failure produces `MessageDeliveryException("Invalid auth token")`.
4. The server replies `CONNECTED`, and Spring publishes `SessionConnectedEvent`.
5. `WebSocketEventListener.handleWebSocketConnectListener()`:
   1. Gets the username and the STOMP `sessionId`.
   2. `PresenceService.sessionConnected(username, sessionId)` adds the session to the in-memory set and returns `true` only if this is the user's first session.
   3. If `true`, `UserService.setOnlineStatus(username, true)` persists `online=true`, then `convertAndSend("/topic/presence", PresencePayload(id, username, true))` broadcasts to everyone.
   4. `MessageService.markPendingAsDelivered(username)` runs Flow 6.
6. The client subscribes to:
   - `/topic/presence`: online/offline of all users
   - `/user/queue/messages`: incoming messages and echoes of the user's own messages
   - `/user/queue/receipts`: status changes for messages the user sent
   - `/user/queue/typing`: typing indicators

---

### Flow 5: Send a message (`/app/chat.send`)

```
Sender ──SEND /app/chat.send {receiverId, content}──►
   ChatController.sendMessage() → MessageService.sendMessage()
       validate → load sender/receiver → status = receiver online ? DELIVERED : SENT
       → save in DB → (after commit) push MessageDto to
             /user/{receiver}/queue/messages   and   /user/{sender}/queue/messages
```

1. The sender's client sends STOMP `SEND` to `/app/chat.send` with `{receiverId, content}`.
2. The `/app` prefix routes it to `ChatController.sendMessage(request, principal)`. The sender comes from the authenticated `principal`, never from the client payload.
3. `MessageService.sendMessage(senderUsername, req)` runs in a `@Transactional` method:
   1. Rejects empty or blank content (`IllegalArgumentException`).
   2. Loads the sender by username and the receiver by id (throws `RuntimeException` if not found).
   3. **Status decision:** `presenceService.isOnline(receiver.username)` is true → `DELIVERED` (✓✓). Otherwise `SENT` (✓).
   4. Builds the `Message` (content trimmed, timestamp now) and calls `messageRepository.save()`.
   5. Converts it to `MessageDto`.
   6. `afterCommit(...)` registers a callback that runs **only after the DB commit succeeds**:
      - `convertAndSendToUser(receiver.username, "/queue/messages", dto)` delivers to every open tab of the receiver.
      - If the receiver is not the sender: `convertAndSendToUser(sender.username, "/queue/messages", dto)` echoes to the sender's tabs, so they get the real id and status.
4. If the receiver is offline, the message stays in the DB with status `SENT` until Flow 6.

> Errors thrown here (empty content, unknown receiver) are **not** sent back to the client, because there is no `@MessageExceptionHandler`. They are only logged.

---

### Flow 6: Offline message delivery (SENT to DELIVERED)

Triggered by Flow 4 when a user connects.

1. `MessageService.markPendingAsDelivered(receiverUsername)`:
   1. Loads the receiver (returns early if not found).
   2. `messageRepository.findByReceiver_IdAndStatus(receiverId, SENT)` gets every message that waited for this user.
   3. Sets each one to `DELIVERED` and calls `saveAll()`.
   4. Groups the message ids by sender username.
   5. After commit, for each sender it calls `notifyStatus(sender, ids, DELIVERED)`, which sends `convertAndSendToUser(sender, "/queue/receipts", MessageStatusUpdate(ids, "DELIVERED"))`.
2. The sender's UI changes ✓ to ✓✓.
3. The newly connected user fetches the actual message contents via `GET /api/messages/history/{otherUserId}` (Flow 3c).

---

### Flow 7: Typing indicator (`/app/chat.typing`)

1. The client sends `{receiverId, typing: true|false}` to `/app/chat.typing`.
2. `ChatController.typing()`:
   1. Loads the sender from `principal`.
   2. Loads the receiver by `payload.receiverId()`.
   3. Sends `convertAndSendToUser(receiver.username, "/queue/typing", TypingPayload(sender.id, receiver.id, typing))`. The `senderId` is overwritten with the authenticated user's id, so it cannot be spoofed.
3. Nothing is persisted.

---

### Flow 8: Read receipts (DELIVERED/SENT to READ)

**Option A, whole conversation via STOMP:** `/app/chat.read {senderId}`
**Option B, whole conversation via REST:** `PUT /api/messages/conversation/{senderId}/read`

Both call `MessageService.markConversationRead(readerUsername, senderId)`:
1. Loads the reader by username.
2. `findBySender_IdAndReceiver_IdAndStatusNot(senderId, readerId, READ)` gets all unread messages from that sender to the reader. Returns early if there are none.
3. Sets each one to `READ` and calls `saveAll()`.
4. After commit, `notifyStatus(senderUsername, ids, READ)` sends to `/user/{sender}/queue/receipts`, and the sender sees blue ✓✓.

**Option C, a single message via REST:** `PUT /api/messages/{messageId}/read`
`MessageController.markAsRead()` → `MessageService.markAsRead(messageId, readerUsername)`:
1. Finds the message. It is updated only if the **caller is the receiver** and it is not already READ.
2. Sets it to READ and saves.
3. After commit, `notifyStatus(sender, [messageId], READ)`.

---

### Flow 9: Disconnect & presence offline

1. The tab closes, the network drops, or the client sends `DISCONNECT`. Spring publishes `SessionDisconnectEvent` (possibly more than once).
2. `WebSocketEventListener.handleWebSocketDisconnectListener()`:
   1. `PresenceService.sessionDisconnected(username, sessionId)` removes the session. It returns `true` only if a session was really removed **and** it was the last one. The map entry is deleted when the set is empty.
   2. If `true`, `UserService.setOnlineStatus(username, false)` runs, then it broadcasts `/topic/presence` `{online:false}`.
3. From then on, new messages to this user are saved with status `SENT` (Flow 5).

---

## 4. Message status lifecycle

```
            receiver offline                receiver connects (Flow 6)
  send ──────────────────────► SENT (✓) ─────────────────────────────► DELIVERED (✓✓)
    │                                                                      │
    │ receiver online                                                      │ receiver opens chat
    └──────────────────────────► DELIVERED (✓✓) ──────────────────────────►│ (Flow 8)
                                                                           ▼
                                                                    READ (blue ✓✓)
```

Each transition is pushed to the **original sender** on `/user/queue/receipts` as `MessageStatusUpdate`.

---

## 5. Quick reference

### REST endpoints

| Method | Path | Auth | Controller → Service | Response |
|---|---|---|---|---|
| POST | `/api/auth/register` | public | `AuthController.register` → `AuthService.register` | 201 `AuthResponse` / 409 |
| POST | `/api/auth/login` | public | `AuthController.login` → `AuthService.login` | 200 `AuthResponse` |
| GET | `/api/users` | JWT | `UserController.getAllUsers` → `UserService.getAllUsers` | `UserDto[]` |
| GET | `/api/users/{id}` | JWT | `UserController.getUserById` → `UserService.getUserById` | `UserDto` |
| GET | `/api/messages/history/{otherUserId}` | JWT | `MessageController.getHistory` → `MessageService.getHistory` | `MessageDto[]` |
| PUT | `/api/messages/{messageId}/read` | JWT | `MessageController.markAsRead` → `MessageService.markAsRead` | 200 |
| PUT | `/api/messages/conversation/{senderId}/read` | JWT | `MessageController.markConversationAsRead` → `MessageService.markConversationRead` | 200 |

### STOMP destinations

| Direction | Destination | Payload | Handler / Producer |
|---|---|---|---|
| Client → Server | `/app/chat.send` | `SendMessageRequest` | `ChatController.sendMessage` |
| Client → Server | `/app/chat.typing` | `TypingPayload` | `ChatController.typing` |
| Client → Server | `/app/chat.read` | `ReadReceiptPayload` | `ChatController.readReceipt` |
| Server → All | `/topic/presence` | `PresencePayload` | `WebSocketEventListener` |
| Server → User | `/user/queue/messages` | `MessageDto` | `MessageService.sendMessage` |
| Server → User | `/user/queue/receipts` | `MessageStatusUpdate` | `MessageService.notifyStatus` |
| Server → User | `/user/queue/typing` | `TypingPayload` | `ChatController.typing` |

### Configuration (`application.properties`)

| Key | Value | Meaning |
|---|---|---|
| `server.port` | 8080 | HTTP/WS port |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/multichat` | DB |
| `spring.jpa.hibernate.ddl-auto` | `update` | auto schema |
| `app.jwt.secret` | Base64 HMAC key | JWT signing |
| `app.jwt.expiration` | 86400000 | 24 h token lifetime |

---

## 6. Observations / known gaps

1. **Wrong password on login returns 500, not 401.** `BadCredentialsException` is not handled specifically, so it falls into the generic `Exception` handler in `GlobalExceptionHandler`.
2. **Expired or malformed JWT in REST calls:** `JwtAuthFilter` does not catch `JwtException` from `extractUsername()`. The exception escapes the filter, so the client gets an error response instead of a clean 401.
3. **STOMP handler errors are silent.** There is no `@MessageExceptionHandler`, so failures in `chat.send` (empty content, bad receiverId) never reach the client.
4. **Presence is in-memory** (`PresenceService`). It only works on a single server instance, and the simple broker has the same limit. Scaling out would need a shared store such as Redis and an external broker such as RabbitMQ.
5. **No input validation** on `RegisterRequest` (blank username or password are accepted).
6. **Secrets in plain text:** the DB password and JWT secret are in `application.properties`. Move them to environment variables for anything beyond local development.
7. `UserService.getUserById()` throws a plain `RuntimeException` → 500 instead of 404.
