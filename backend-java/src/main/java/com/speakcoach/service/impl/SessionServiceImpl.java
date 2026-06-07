// SessionServiceImpl — 口语会话生命周期：创建/获取/列出/结束/对象映射。
package com.speakcoach.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakcoach.dto.SessionDtos;
import com.speakcoach.entity.SpeakingSession;
import com.speakcoach.entity.UserAbilityProfile;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.SpeakingSessionMapper;
import com.speakcoach.mapper.UserAbilityProfileMapper;
import com.speakcoach.service.SessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 口语会话服务实现
 * <ul>
 *   <li>create：生成 UUID，初始化 active/turnCount=0/updatedAt=now，saveAndFlush + 重读以拿到 created_at</li>
 *   <li>getForUser：findById 后校验 userId，不匹配返回 403</li>
 *   <li>listForUser：直接走 {@code findByUserIdOrderByCreatedAtDesc}</li>
 *   <li>finish：加载会话 → 校验归属 → 写入 summary → 同步 user_ability_profile（聚合分数）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionServiceImpl implements SessionService {

    /** 首次使用且不存在画像记录的用户使用的默认分值。 */
    private static final int DEFAULT_ABILITY_SCORE = 60;

    private final SpeakingSessionMapper sessionMapper;
    private final UserAbilityProfileMapper userAbilityProfileMapper;
    private final ObjectMapper objectMapper;

    /**
     * 合法场景标识的白名单。与 {@code frontend/src/scenes.ts} 中的权威列表保持一致，
     * 以便后端拒绝前端仪表盘永远不会发送的任意字符串。如目录变化请保持同步。
     */
    private static final Set<String> VALID_SCENES = Set.of(
            "interview",
            "travel",
            "daily_chat",
            "business"
    );

    /**
     * 校验请求中的 scene id 是否在白名单中。当输入为 null、空字符串或未知场景时，
     * 抛出 {@link ApiException}，HTTP 400 / 错误码 {@code invalid_scene}。
     * 在 {@link #create(Long, String)} 写库之前调用。
     */
    static void validateScene(String scene) {
        if (scene == null || scene.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_scene",
                    "Scene is required and must be a non-empty string");
        }
        if (!VALID_SCENES.contains(scene)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_scene",
                    "Unknown scene '" + scene + "'. Valid scenes: " + VALID_SCENES);
        }
    }

    @Override
    @Transactional
    public SpeakingSession create(Long userId, String scene) {
        validateScene(scene);
        SpeakingSession session = SpeakingSession.builder()
                .id(UUID.randomUUID().toString())
                .userId(userId)
                .scene(scene)
                .status("active")
                .turnCount(0)
                .updatedAt(java.time.LocalDateTime.now())
                .build();
        sessionMapper.insert(session);
        // 重新读取一次，让数据库侧的默认值（created_at / updated_at）填充到实体。
        return Optional.ofNullable(sessionMapper.selectById(session.getId())).orElse(session);
    }

    @Override
    @Transactional(readOnly = true)
    public SpeakingSession getForUser(String sessionId, Long userId) {
        SpeakingSession session = Optional.ofNullable(sessionMapper.selectById(sessionId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "session_not_found", "Session not found"));
        if (!session.getUserId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "session_forbidden", "Session does not belong to current user");
        }
        return session;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SpeakingSession> listForUser(Long userId) {
        return sessionMapper.selectByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    @Transactional
    public SpeakingSession finish(Long userId, String sessionId, SessionDtos.FinishSessionRequest request) {
        SpeakingSession session = getForUser(sessionId, userId);
        if ("finished".equals(session.getStatus())) {
            // 幂等：对同一会话的第二次 finish 是空操作。
            return session;
        }
        session.setStatus("finished");
        if (request != null) {
            session.setSummary(normaliseSummary(request));
        }
        session.setUpdatedAt(java.time.LocalDateTime.now());
        sessionMapper.updateById(session);
        SpeakingSession saved = session;

        if (request != null) {
            // #R —— 将实际回合数（最小为 1）传入画像加权计算，
            // 使 30 回合的会话对长期画像的影响合理地大于 1 回合会话。
            // 此前硬编码的 `turns=1` 把所有 finish 都收敛到 10% 的会话权重，
            // 导致长会话对用户能力画像的影响被低估。
            int turns = session.getTurnCount() == null ? 1 : Math.max(1, session.getTurnCount());
            updateAbilityProfile(userId, request, turns);
        }
        return saved;
    }

    @Override
    public SessionDtos.SessionResponse toResponse(SpeakingSession s) {
        return new SessionDtos.SessionResponse(
                s.getId(),
                s.getUserId(),
                s.getScene(),
                s.getStatus(),
                s.getTurnCount(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }

    /**
     * 构造一个 JSON 字符串来汇总 finish 请求。若客户端传入的是普通字符串，
     * 则回退为最小的 {@code {"raw": "..."}} 负载。
     */
    private String normaliseSummary(SessionDtos.FinishSessionRequest request) {
        if (request.summary() == null || request.summary().isBlank()) {
            return null;
        }
        String raw = request.summary();
        // 如果客户端传入的已是 JSON 字符串，则原样保留；否则进行包装。
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                objectMapper.readTree(trimmed);
                return trimmed;
            } catch (JsonProcessingException ex) {
                // 解析失败则落入下面的包装分支
            }
        }
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("raw", raw));
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise session summary: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 将会话各维度得分聚合到用户的长期画像中。
     * 策略：对（历史值）与（本次会话值）做加权平均，会话权重 = min(1, turns/10)，
     * 以避免单次短会话主导既有画像。首次使用的用户将获得以本次会话值作为种子的画像行。
     *
     * @param turns 本次会话已完成的 chat 回合数（>= 1）。
     */
    private void updateAbilityProfile(Long userId, SessionDtos.FinishSessionRequest request, int turns) {
        UserAbilityProfile existingProfile = userAbilityProfileMapper.selectById(userId);
        boolean exists = existingProfile != null;
        UserAbilityProfile profile = exists
                ? existingProfile
                : UserAbilityProfile.builder()
                        .userId(userId)
                        .grammarScore(DEFAULT_ABILITY_SCORE)
                        .vocabularyScore(DEFAULT_ABILITY_SCORE)
                        .fluencyScore(DEFAULT_ABILITY_SCORE)
                        .logicScore(DEFAULT_ABILITY_SCORE)
                        .build();

        profile.setGrammarScore(blend(profile.getGrammarScore(), request.grammarScore(), turns));
        profile.setVocabularyScore(blend(profile.getVocabularyScore(), request.vocabularyScore(), turns));
        profile.setFluencyScore(blend(profile.getFluencyScore(), request.fluencyScore(), turns));
        profile.setLogicScore(blend(profile.getLogicScore(), request.logicScore(), turns));
        profile.setCommonErrors(mergeCommonErrors(profile.getCommonErrors(), request.commonErrors()));
        profile.setUpdatedAt(java.time.LocalDateTime.now());
        if (exists) {
            userAbilityProfileMapper.updateById(profile);
        } else {
            userAbilityProfileMapper.insert(profile);
        }
    }

    private static int blend(Integer existing, Integer sessionValue, int turns) {
        if (sessionValue == null) {
            return existing == null ? DEFAULT_ABILITY_SCORE : existing;
        }
        if (existing == null) {
            return clamp(sessionValue);
        }
        double sessionWeight = Math.min(1.0, Math.max(0.1, turns / 10.0));
        double blended = existing * (1.0 - sessionWeight) + sessionValue * sessionWeight;
        return clamp((int) Math.round(blended));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private String mergeCommonErrors(String existingJson, List<String> sessionErrors) {
        if (sessionErrors == null || sessionErrors.isEmpty()) {
            return existingJson;
        }
        List<String> merged = new ArrayList<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                List<String> prev = objectMapper.readValue(existingJson,
                        objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
                if (prev != null) {
                    merged.addAll(prev);
                }
            } catch (JsonProcessingException ex) {
                log.debug("Existing common_errors not parseable, ignoring: {}", ex.getMessage());
            }
        }
        for (String err : sessionErrors) {
            if (err != null && !err.isBlank() && !merged.contains(err)) {
                merged.add(err);
            }
        }
        if (merged.isEmpty()) {
            return existingJson;
        }
        try {
            return objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialise common_errors: {}", ex.getMessage());
            return existingJson;
        }
    }
}
