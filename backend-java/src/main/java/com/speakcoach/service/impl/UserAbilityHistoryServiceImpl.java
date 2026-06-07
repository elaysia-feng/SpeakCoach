// UserAbilityHistoryServiceImpl —— M1-A 长期记忆里程碑的实现。
// 关键约束（与 AuditServiceImpl 保持一致）：
//   * 任何写库失败都被 try/catch 吞掉，仅记录 WARN 日志，绝不破坏 /api/chat 主链。
//   * 写入路径在事务外执行（不持有 TurnService 的写事务），失败不影响其他落库。
package com.speakcoach.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakcoach.dto.AuthDtos;
import com.speakcoach.entity.UserAbilityHistory;
import com.speakcoach.entity.UserAbilityProfile;
import com.speakcoach.mapper.UserAbilityHistoryMapper;
import com.speakcoach.service.UserAbilityHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 能力历史快照服务实现
 * <p>每次 {@code TurnServiceImpl.handleTurn} 完成后调用本服务写一条快照，
 * 同时也供 Python 侧 save_report 节点通过 {@code /api/internal/profile/snapshot}
 * 调用 —— 这样即便 AI 节点先把 ability_score 写到了 speaking_turn，Java 这边
 * 也能补上一份"长寿命"的历史行。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAbilityHistoryServiceImpl implements UserAbilityHistoryService {

    /** 拉取 timeline 时允许的硬上限。 */
    private static final int MAX_TIMELINE_LIMIT = 100;

    private final UserAbilityHistoryMapper historyMapper;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean snapshot(Long userId, String sessionId, Integer turnId, UserAbilityProfile profile) {
        if (userId == null || profile == null) {
            // 缺关键字段直接当作成功返回 —— 不影响主链。
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", userId);
            payload.put("grammar_score", profile.getGrammarScore());
            payload.put("vocabulary_score", profile.getVocabularyScore());
            payload.put("fluency_score", profile.getFluencyScore());
            payload.put("logic_score", profile.getLogicScore());
            payload.put("common_errors", profile.getCommonErrors());
            payload.put("cefr_level", profile.getCefrLevel());
            String json = objectMapper.writeValueAsString(payload);

            UserAbilityHistory row = UserAbilityHistory.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .snapshotJson(json)
                    .build();
            int inserted = historyMapper.insert(row);
            return inserted > 0;
        } catch (Exception ex) {
            log.warn("user_ability_history.snapshot failed for userId={} sessionId={} turnId={}: {}",
                    userId, sessionId, turnId, ex.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean snapshotFromRequest(AuthDtos.AbilityHistorySnapshotRequest request) {
        if (request == null || request.userId() == null) {
            return false;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("user_id", request.userId());
            payload.put("grammar_score", request.grammarScore());
            payload.put("vocabulary_score", request.vocabularyScore());
            payload.put("fluency_score", request.fluencyScore());
            payload.put("logic_score", request.logicScore());
            payload.put("common_errors", request.commonErrors());
            String json = objectMapper.writeValueAsString(payload);

            UserAbilityHistory row = UserAbilityHistory.builder()
                    .userId(request.userId())
                    .sessionId(request.sessionId())
                    .turnId(request.turnId())
                    .snapshotJson(json)
                    .build();
            int inserted = historyMapper.insert(row);
            if (inserted > 0) {
                log.info("Snapshot recorded for userId={} sessionId={} turnId={}",
                        request.userId(), request.sessionId(), request.turnId());
            }
            return inserted > 0;
        } catch (Exception ex) {
            log.warn("user_ability_history.snapshotFromRequest failed for userId={}: {}",
                    request.userId(), ex.getMessage());
            return false;
        }
    }

    @Override
    public List<AuthDtos.AbilityHistoryEntry> getTimeline(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(MAX_TIMELINE_LIMIT, limit));
        return historyMapper.selectRecentByUserId(userId, safeLimit).stream()
                .map(this::toEntry)
                .collect(Collectors.toList());
    }

    private AuthDtos.AbilityHistoryEntry toEntry(UserAbilityHistory row) {
        String commonErrors = null;
        String cefrLevel = null;
        try {
            if (row.getSnapshotJson() != null && !row.getSnapshotJson().isBlank()) {
                Map<?, ?> map = objectMapper.readValue(row.getSnapshotJson(), Map.class);
                Object ce = map.get("common_errors");
                if (ce != null) {
                    commonErrors = objectMapper.writeValueAsString(ce);
                }
                Object lvl = map.get("cefr_level");
                if (lvl != null) {
                    cefrLevel = String.valueOf(lvl);
                }
            }
        } catch (JsonProcessingException ex) {
            // 忽略：保留 null 给前端，让它用整段 snapshotJson 自行兜底。
        }
        return new AuthDtos.AbilityHistoryEntry(
                row.getId(),
                row.getSessionId(),
                row.getTurnId(),
                extractInt(row, "grammar_score"),
                extractInt(row, "vocabulary_score"),
                extractInt(row, "fluency_score"),
                extractInt(row, "logic_score"),
                commonErrors,
                cefrLevel,
                row.getSnapshotJson(),
                row.getCreatedAt() != null ? row.getCreatedAt().toString() : null
        );
    }

    private Integer extractInt(UserAbilityHistory row, String key) {
        try {
            if (row.getSnapshotJson() == null || row.getSnapshotJson().isBlank()) {
                return null;
            }
            Map<?, ?> map = objectMapper.readValue(row.getSnapshotJson(), Map.class);
            Object v = map.get(key);
            if (v instanceof Number n) {
                return n.intValue();
            }
            if (v != null) {
                try {
                    return Integer.valueOf(String.valueOf(v));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
        } catch (JsonProcessingException ex) {
            // 忽略，返回 null。
        }
        return null;
    }
}
