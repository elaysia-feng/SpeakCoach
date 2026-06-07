// 错题本相关 DTO 记录。
package com.speakcoach.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ErrorBookDtos {

    private ErrorBookDtos() {}

    /**
     * 单条错题。前端 /api/users/me/error-book 与 /api/users/me/error-book/stats
     * 共用的响应形状。
     */
    public record ErrorBookEntry(
            Long id,
            String type,
            String original,
            String corrected,
            String explanation,
            String sourceSessionId,
            Integer sourceTurnId,
            Integer masteryLevel,
            LocalDateTime nextReviewAt,
            Integer reviewCount,
            LocalDateTime createdAt
    ) {}

    public record ErrorBookListResponse(
            List<ErrorBookEntry> entries
    ) {}

    public record ErrorBookStatsResponse(
            Map<String, Integer> byType
    ) {}

    /**
     * 内网端点 {@code POST /api/internal/error-book/batch} 的请求体 —— 供
     * Python 写错题本节点调用。仅传 correction 列表，其它列由 Java 端填充。
     */
    public record BatchAddRequest(
            String sessionId,
            Integer turnId,
            List<CorrectionItem> corrections
    ) {}

    public record CorrectionItem(
            String type,
            String original,
            String corrected,
            String explanation
    ) {}

    /** 批量写入的响应：实际新增的行数（去重后）。 */
    public record BatchAddResponse(
            int inserted
    ) {}
}
