// chat 控制器的 DTO —— 返回给前端的请求与响应数据形状。
package com.speakcoach.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class ChatDtos {

    private ChatDtos() {}

    public record ChatRequest(
            @NotBlank String sessionId,
            @NotBlank @Size(max = 4000) String userText,
            /**
             * 客户端可选提供的 scene 覆盖。权威值是 {@code speaking_session}
             * 行上存储的 scene（创建会话时设定）；该字段仅为向前兼容而存在，
             * 当前仅起信息提示作用。
             * {@link com.speakcoach.service.TurnService} 始终从会话中读取 scene，
             * 因此调用方可省略此字段。
             */
            String scene
    ) {}

    public record ChatResponse(
            Integer turnId,
            String userText,
            String userAudioUrl,
            String aiReply,
            String audioUrl,
            String corrections,
            String shadowAnswer,
            String abilityScore,
            String pronunciationScore,
            String strategy,
            String summary,
            String speechMetrics,
            String wordTimestamps
    ) {}

    /** chat 历史中的单个回合（若开放 GET /api/chat?sessionId=... 则使用）。 */
    public record ChatHistoryItem(
            Integer turnId,
            String userText,
            String userAudioUrl,
            String aiReply,
            String audioUrl,
            String corrections,
            String shadowAnswer,
            String abilityScore,
            String pronunciationScore,
            String strategy,
            String summary,
            String speechMetrics,
            String wordTimestamps
    ) {}

    /** chat 历史响应的包装类型。 */
    public record ChatHistoryResponse(
            String sessionId,
            List<ChatHistoryItem> turns
    ) {}
}
