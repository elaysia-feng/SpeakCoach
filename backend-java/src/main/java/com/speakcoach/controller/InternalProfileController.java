// InternalProfileController — M1-A 内网端点。
// 供 Python 侧的 save_report 节点回调，把当轮 ability_score 推送到
// user_ability_history。同时给 load_user_profile 节点读画像用。
// 鉴权：通过 {@code X-Internal-Token} header 校验；与 {@code app.internal.token}
// 环境变量配对。所有调用都来自 backend-python（同一内网，不向公网暴露）。
package com.speakcoach.controller;

import com.speakcoach.dto.AuthDtos;
import com.speakcoach.entity.UserAbilityProfile;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.UserAbilityProfileMapper;
import com.speakcoach.service.UserAbilityHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/internal/profile")
@RequiredArgsConstructor
@Slf4j
public class InternalProfileController {

    private final UserAbilityProfileMapper userAbilityProfileMapper;
    private final UserAbilityHistoryService abilityHistoryService;

    /**
     * 与 {@code INTERNAL_API_TOKEN} 环境变量配对的内网 token。空字符串表示
     * "不校验"（开发模式）；非空时必须与 {@code X-Internal-Token} header 完全相等。
     */
    @Value("${app.internal.token:}")
    private String internalToken;

    /**
     * GET /api/internal/profile/{userId}
     * 读画像。供 Python {@code load_user_profile} 节点使用。
     */
    @GetMapping("/{userId}")
    public ResponseEntity<?> getProfile(
            @PathVariable Long userId,
            @RequestHeader(value = "X-Internal-Token", required = false) String token) {
        requireInternalToken(token);
        UserAbilityProfile profile = Optional.ofNullable(userAbilityProfileMapper.selectById(userId))
                .orElse(null);
        if (profile == null) {
            return ResponseEntity.ok(Map.of(
                    "userId", userId,
                    "exists", false
            ));
        }
        return ResponseEntity.ok(Map.of(
                "userId", profile.getUserId(),
                "grammarScore", profile.getGrammarScore(),
                "vocabularyScore", profile.getVocabularyScore(),
                "fluencyScore", profile.getFluencyScore(),
                "logicScore", profile.getLogicScore(),
                "commonErrors", profile.getCommonErrors(),
                "cefrLevel", profile.getCefrLevel(),
                "exists", true
        ));
    }

    /**
     * POST /api/internal/profile/snapshot
     * Python save_report 节点回调 —— 把本轮 ability_score 写入 user_ability_history。
     * 写入失败按旁路处理（service impl 内部 try/catch），但会返回 200 让 Python
     * 不会因为审计写入失败而重试。
     */
    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> snapshot(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody AuthDtos.AbilityHistorySnapshotRequest request) {
        requireInternalToken(token);
        if (request == null || request.userId() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request",
                    "userId is required");
        }
        boolean ok = abilityHistoryService.snapshotFromRequest(request);
        return ResponseEntity.ok(Map.of(
                "ok", ok,
                "userId", request.userId(),
                "sessionId", request.sessionId() == null ? "" : request.sessionId(),
                "turnId", request.turnId() == null ? 0 : request.turnId()
        ));
    }

    private void requireInternalToken(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            // 未配置 token —— 开发模式放行（与现有 email / jwt 占位行为一致）。
            return;
        }
        if (token == null || !internalToken.equals(token)) {
            log.warn("Internal profile endpoint rejected missing/invalid X-Internal-Token");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "internal_token_invalid",
                    "X-Internal-Token header is missing or invalid");
        }
    }
}
