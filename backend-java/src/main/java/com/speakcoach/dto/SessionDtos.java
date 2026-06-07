// 会话控制器的 DTO —— 创建/列出/结束/响应的数据形状。
package com.speakcoach.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

public class SessionDtos {

    private SessionDtos() {}

    public record CreateSessionRequest(
            @NotBlank @Size(max = 32) String scene
    ) {}

    public record SessionResponse(
            String sessionId,
            Long userId,
            String scene,
            String status,
            Integer turnCount,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    /** GET /api/sessions 的包装类型 —— 返回当前用户的会话列表。 */
    public record SessionListResponse(
            List<SessionResponse> sessions
    ) {}

    /**
     * POST /api/sessions/{id}/finish 的请求体。客户端可提交一份由 AI 生成的
     * 自由格式摘要（已是序列化后的 JSON 字符串），并附带整体评分与
     * 本次会话中检测到的常见错误模式列表。
     */
    public record FinishSessionRequest(
            String summary,
            Integer grammarScore,
            Integer vocabularyScore,
            Integer fluencyScore,
            Integer logicScore,
            List<String> commonErrors
    ) {}

    /** POST /api/sessions/{id}/finish 的响应 —— 反映 finish 后已持久化的会话。 */
    public record SessionSummaryResponse(
            SessionResponse session,
            String summary,
            Integer grammarScore,
            Integer vocabularyScore,
            Integer fluencyScore,
            Integer logicScore
    ) {}
}
