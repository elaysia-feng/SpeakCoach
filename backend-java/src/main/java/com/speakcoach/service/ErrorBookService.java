// ErrorBookService — 错题本契约。
package com.speakcoach.service;

import com.speakcoach.dto.ErrorBookDtos;

import java.util.List;
import java.util.Map;

/**
 * 用户错题本服务接口
 * <ul>
 *   <li>addBatch：批量写入 corrections 列表；按 (user, original, corrected) 去重，已存在的不报错</li>
 *   <li>markMastered：把某条错题标记为已掌握（mastery_level=3, review_count+1, next_review_at = now + 7d）</li>
 *   <li>listForReview：按 (mastery, type) 过滤并按 next_review_at 升序返回</li>
 *   <li>statsByType：返回最近 7 天按 type 聚合的新增数</li>
 * </ul>
 */
public interface ErrorBookService {

    /**
     * 批量写入错题。已存在的 (user, original, corrected) 不会重复插入；函数返回
     * 实际新增的行数。即使整批全部已存在，也不会抛异常。
     */
    int addBatch(Long userId, String sessionId, Integer turnId, List<ErrorBookDtos.CorrectionItem> corrections);

    /**
     * 标记某条错题为已掌握。返回是否成功（id 不存在或不属于该用户时返回 false）。
     */
    boolean markMastered(Long userId, Long entryId);

    /**
     * 列出某用户的错题。{@code masteryLevel} 与 {@code type} 均可为 null（不过滤）。
     * {@code limit} <= 0 表示不限制条数。
     */
    List<ErrorBookDtos.ErrorBookEntry> listForUser(Long userId, Integer masteryLevel, String type, int limit);

    /**
     * 返回最近 7 天按 type 聚合的新增数（保证 4 个 type 键都存在，缺省为 0）。
     */
    Map<String, Integer> statsByType(Long userId);
}
