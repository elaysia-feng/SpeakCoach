// ErrorBookServiceImpl — 错题本读写实现。
// 写错题本失败不抛错（吞掉 + 日志），让上游 /api/chat 主链不受影响。
package com.speakcoach.service.impl;

import com.speakcoach.dto.ErrorBookDtos;
import com.speakcoach.entity.UserErrorBook;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.UserErrorBookMapper;
import com.speakcoach.service.ErrorBookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 错题本服务实现
 * <ul>
 *   <li>addBatch：以 (user, original, corrected) 三元组去重；单条 UNIQUE 冲突
 *   视为已存在，继续处理剩余条目；返回值是实际新增的行数</li>
 *   <li>markMastered：把 mastery_level 设为 3，review_count + 1，
 *   next_review_at = now + 7 days</li>
 *   <li>listForUser：按 mastery/type 过滤，按 next_review_at 升序</li>
 *   <li>statsByType：返回最近 7 天按 type 聚合，4 个 type 键都填 0 兜底</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorBookServiceImpl implements ErrorBookService {

    /** 标记为已掌握之后的下一次推荐复习时间（天数）。 */
    private static final int NEXT_REVIEW_DAYS_ON_MASTER = 7;

    /** 4 个合法的 type —— 不在白名单的入参会被归一化为 grammar。 */
    private static final Set<String> VALID_TYPES = Set.of("grammar", "vocab", "fluency", "logic");

    private final UserErrorBookMapper errorBookMapper;

    @Override
    @Transactional
    public int addBatch(Long userId, String sessionId, Integer turnId, List<ErrorBookDtos.CorrectionItem> corrections) {
        if (userId == null || corrections == null || corrections.isEmpty()) {
            return 0;
        }
        int inserted = 0;
        for (ErrorBookDtos.CorrectionItem item : corrections) {
            if (item == null) continue;
            String original = item.original() == null ? "" : item.original().trim();
            String corrected = item.corrected() == null ? "" : item.corrected().trim();
            if (original.isEmpty() || corrected.isEmpty()) {
                continue;
            }
            String type = normalizeType(item.type());
            UserErrorBook row = UserErrorBook.builder()
                    .userId(userId)
                    .type(type)
                    .original(original)
                    .corrected(corrected)
                    .explanation(item.explanation())
                    .sourceSessionId(sessionId)
                    .sourceTurnId(turnId)
                    .masteryLevel(0)
                    .nextReviewAt(null)
                    .reviewCount(0)
                    .updatedAt(LocalDateTime.now())
                    .build();
            try {
                int affected = errorBookMapper.insert(row);
                if (affected > 0) inserted++;
            } catch (DuplicateKeyException ex) {
                // 三元组已存在 —— 这是预期路径，安静地吞掉。
                log.debug("Duplicate error-book entry (user={}, original={}): {}",
                        userId, abbreviate(original), ex.getMessage());
            } catch (Exception ex) {
                // 写错题本失败不阻断主链，但记日志以便诊断。
                log.warn("Failed to insert error-book entry (user={}): {}", userId, ex.getMessage());
            }
        }
        return inserted;
    }

    @Override
    @Transactional
    public boolean markMastered(Long userId, Long entryId) {
        if (userId == null || entryId == null) {
            return false;
        }
        UserErrorBook row = errorBookMapper.selectById(entryId);
        if (row == null || !row.getUserId().equals(userId)) {
            // 不暴露"记录属于其它用户"的细节。
            return false;
        }
        row.setMasteryLevel(3);
        row.setReviewCount((row.getReviewCount() == null ? 0 : row.getReviewCount()) + 1);
        row.setNextReviewAt(LocalDateTime.now().plusDays(NEXT_REVIEW_DAYS_ON_MASTER));
        row.setUpdatedAt(LocalDateTime.now());
        return errorBookMapper.updateById(row) > 0;
    }

    @Override
    public List<ErrorBookDtos.ErrorBookEntry> listForUser(Long userId, Integer masteryLevel, String type, int limit) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
        String normalizedType = (type == null || type.isBlank()) ? null : normalizeType(type);
        int normalizedLimit = limit <= 0 ? 0 : limit;
        return errorBookMapper.selectForUser(userId, masteryLevel, normalizedType, normalizedLimit).stream()
                .map(ErrorBookServiceImpl::toEntry)
                .toList();
    }

    @Override
    public Map<String, Integer> statsByType(Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
        // 4 个 type 键都保证存在 —— 缺省为 0。
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String t : VALID_TYPES) {
            counts.put(t, 0);
        }
        for (Map<String, Object> row : errorBookMapper.countByTypeSince(userId)) {
            Object typeObj = row.get("type");
            Object cntObj = row.get("cnt");
            if (typeObj == null || cntObj == null) continue;
            String type = typeObj.toString();
            if (!VALID_TYPES.contains(type)) continue;
            counts.put(type, ((Number) cntObj).intValue());
        }
        return counts;
    }

    private static String normalizeType(String raw) {
        if (raw == null) return "grammar";
        String trimmed = raw.trim().toLowerCase();
        return VALID_TYPES.contains(trimmed) ? trimmed : "grammar";
    }

    private static ErrorBookDtos.ErrorBookEntry toEntry(UserErrorBook row) {
        return new ErrorBookDtos.ErrorBookEntry(
                row.getId(),
                row.getType(),
                row.getOriginal(),
                row.getCorrected(),
                row.getExplanation(),
                row.getSourceSessionId(),
                row.getSourceTurnId(),
                row.getMasteryLevel(),
                row.getNextReviewAt(),
                row.getReviewCount(),
                row.getCreatedAt()
        );
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "..." : s;
    }
}
