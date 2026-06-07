// InternalErrorBookController — 内网端点，由 Python write_error_book 节点调用。
// 不走 JWT 鉴权，依赖内网隔离 / security config 的 permit-all。
package com.speakcoach.controller;

import com.speakcoach.dto.ErrorBookDtos;
import com.speakcoach.exception.ApiException;
import com.speakcoach.service.ErrorBookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/error-book")
@RequiredArgsConstructor
public class InternalErrorBookController {

    /**
     * 共享密钥头 —— 与 application.yml 中的 {@code app.internal.shared-secret}
     * 匹配。环境变量 {@code INTERNAL_SHARED_SECRET} 可覆盖默认值。
     * 没有走 Spring Security 是因为这是内网端点（仅 Java ↔ Python 之间）。
     */
    private static final String SHARED_SECRET_HEADER = "X-Internal-Secret";

    private final ErrorBookService errorBookService;

    /**
     * POST /api/internal/error-book/batch —— Python 节点每 turn 结束时调用。
     * 把 corrections 批量写入对应用户 (userId 必须显式传) 的错题本。
     */
    @PostMapping("/batch")
    public ResponseEntity<ErrorBookDtos.BatchAddResponse> batchAdd(
            @RequestHeader(value = SHARED_SECRET_HEADER, required = false) String secret,
            @RequestHeader(value = "X-User-Id", required = false) String userIdHeader,
            @RequestBody ErrorBookDtos.BatchAddRequest request) {
        if (secret == null || secret.isBlank() || !SecretVerifier.matches(secret)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "forbidden",
                    "Invalid internal secret");
        }
        if (userIdHeader == null || userIdHeader.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "user_required",
                    "X-User-Id header is required");
        }
        Long userId;
        try {
            userId = Long.valueOf(userIdHeader);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_user_id",
                    "X-User-Id must be a numeric user id");
        }
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request",
                    "Request body is required");
        }
        int inserted = errorBookService.addBatch(
                userId, request.sessionId(), request.turnId(), request.corrections());
        return ResponseEntity.ok(new ErrorBookDtos.BatchAddResponse(inserted));
    }
}
