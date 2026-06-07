// SessionController — 创建/获取/列出/结束口语会话。
package com.speakcoach.controller;

import com.speakcoach.dto.SessionDtos;
import com.speakcoach.entity.SpeakingSession;
import com.speakcoach.exception.ApiException;
import com.speakcoach.security.AuthPrincipal;
import com.speakcoach.service.SessionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final SessionService sessionService;

    /** POST /api/sessions — 为当前用户创建一个新会话。 */
    @PostMapping
    public ResponseEntity<SessionDtos.SessionResponse> create(
            @Valid @RequestBody SessionDtos.CreateSessionRequest req) {
        Long userId = currentUserId();
        SpeakingSession session = sessionService.create(userId, req.scene());
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.toResponse(session));
    }

    /** POST /api/sessions/create_session — 前端 New 场景选择后的显式创建入口。 */
    @PostMapping("/create_session")
    public ResponseEntity<SessionDtos.SessionResponse> createSession(
            @Valid @RequestBody SessionDtos.CreateSessionRequest req) {
        return create(req);
    }

    /** GET /api/sessions/{id} — 获取单个会话（带归属权校验）。 */
    @GetMapping("/{id}")
    public ResponseEntity<SessionDtos.SessionResponse> get(@PathVariable String id) {
        Long userId = currentUserId();
        SpeakingSession session = sessionService.getForUser(id, userId);
        return ResponseEntity.ok(sessionService.toResponse(session));
    }

    /** GET /api/sessions — 列出当前用户的全部会话，按时间倒序。 */
    @GetMapping
    public ResponseEntity<SessionDtos.SessionListResponse> list() {
        Long userId = currentUserId();
        List<SpeakingSession> sessions = sessionService.listForUser(userId);
        List<SessionDtos.SessionResponse> mapped = sessions.stream()
                .map(sessionService::toResponse)
                .toList();
        return ResponseEntity.ok(new SessionDtos.SessionListResponse(mapped));
    }

    /** POST /api/sessions/{id}/finish — 将会话标记为已完成并持久化摘要。 */
    @PostMapping("/{id}/finish")
    public ResponseEntity<SessionDtos.SessionResponse> finish(
            @PathVariable String id,
            @RequestBody(required = false) SessionDtos.FinishSessionRequest body) {
        Long userId = currentUserId();
        SpeakingSession session = sessionService.finish(userId, id, body);
        return ResponseEntity.ok(sessionService.toResponse(session));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
        return principal.userId();
    }
}
