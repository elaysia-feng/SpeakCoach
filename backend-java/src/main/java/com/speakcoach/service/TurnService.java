// TurnService — 单次 chat 回合编排契约。
// 实现层负责加载会话、调用 Python AI 服务、持久化回合与审计日志。
package com.speakcoach.service;

import com.speakcoach.dto.ChatDtos;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 对话回合服务接口
 * <p>一次 chat 回合的完整编排：校验会话归属 → 自增 turn_count → 调用 Python AI 服务 →
 * 写入 {@code speaking_turn} → 写入 {@code audit_log} → 返回响应。</p>
 */
public interface TurnService {

    /**
     * 处理一次用户发言。
     *
     * @param userId  当前登录用户的 ID（{@code Long} 主键）
     * @param request 聊天请求（sessionId + userText + 可选 scene）
     * @return AI 回复 + 音频地址 / 纠错 / 影子跟读 / 下一轮策略
     * @throws com.speakcoach.exception.ApiException 404 {@code session_not_found} /
     *                                             403 {@code session_forbidden}
     */
    ChatDtos.ChatResponse handleTurn(Long userId, ChatDtos.ChatRequest request);

    /**
     * 处理一次用户录音发言。音频由 Java 先落盘，Python 负责 WhisperX 转写与语音分析。
     */
    ChatDtos.ChatResponse handleAudioTurn(
            Long userId,
            String sessionId,
            MultipartFile audio,
            String clientTranscript,
            String practiceMode,
            String practiceTarget
    );

    /**
     * 返回一个会话的历史回合，用于前端恢复页面时回放用户录音和 AI TTS。
     */
    List<ChatDtos.ChatHistoryItem> listHistory(Long userId, String sessionId);
}
