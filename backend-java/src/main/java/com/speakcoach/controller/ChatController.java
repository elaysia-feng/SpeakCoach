// ChatController — 单回合端点，委托给 TurnService 处理。
package com.speakcoach.controller;

import com.speakcoach.dto.ChatDtos;
import com.speakcoach.exception.ApiException;
import com.speakcoach.security.AuthPrincipal;
import com.speakcoach.service.TurnService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final TurnService turnService;

    /** POST /api/chat — 单次 chat 回合：校验归属权，调用 Python，持久化并审计。 */
    @PostMapping
    public ResponseEntity<ChatDtos.ChatResponse> chat(@Valid @RequestBody ChatDtos.ChatRequest req) {
        Long userId = currentUserId();
        return ResponseEntity.ok(turnService.handleTurn(userId, req));
    }

    /** POST /api/chat/audio — 上传用户录音，后端转写后再进入原 AI turn 流程。 */
    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ChatDtos.ChatResponse> audioChat(
            @RequestParam String sessionId,
            @RequestParam(required = false) String clientTranscript,
            @RequestParam(required = false) String practiceMode,
            @RequestParam(required = false) String practiceTarget,
            @RequestPart("audio") MultipartFile audio) {
        Long userId = currentUserId();
        return ResponseEntity.ok(turnService.handleAudioTurn(
                userId,
                sessionId,
                audio,
                clientTranscript,
                practiceMode,
                practiceTarget
        ));
    }

    /** GET /api/chat/history?sessionId=... — 回放历史回合。 */
    @GetMapping("/history")
    public ResponseEntity<ChatDtos.ChatHistoryResponse> history(@RequestParam String sessionId) {
        Long userId = currentUserId();
        return ResponseEntity.ok(new ChatDtos.ChatHistoryResponse(
                sessionId,
                turnService.listHistory(userId, sessionId)
        ));
    }

    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
        return principal.userId();
    }
}
