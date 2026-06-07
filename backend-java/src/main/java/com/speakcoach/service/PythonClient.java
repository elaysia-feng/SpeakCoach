// PythonClient — 内部 Python FastAPI 服务的网关。
// 通过接口隔离实现，便于在测试中替换为 fake/stub。
package com.speakcoach.service;

/**
 * Python AI 网关客户端接口
 * <p>所有对 Python FastAPI 服务的 HTTP 调用都通过这个接口收敛，方便在测试中替换成 stub
 * 或本地直连的 Mock 实现。</p>
 */
public interface PythonClient {

    /**
     * 调用 Python 的 {@code /internal/turn} 接口，完成一轮 AI 推理。
     *
     * @param userId    用户 ID
     * @param sessionId 会话 ID
     * @param turnId    回合 ID
     * @param userText  用户输入文本
     * @param scene     场景标识（从 {@code speaking_session.scene} 读取，例 {@code free_talk} / {@code interview} / {@code travel}）。
     *                  必须非空，调用方应保证。原先在 Python 侧有 {@code "free_talk"} 兜底，
     *                  该兜底在 {@code routes/internal.py} 中已移除。
     * @return AI 回复与产物（音频地址、纠错、影子跟读、下一轮策略）
     */
    TurnResult turn(Long userId, String sessionId, int turnId, String userText, String scene,
                   String coachPersona, String preferredVoice);

    /**
     * 调用 Python 的 {@code /internal/audio-turn} 接口，先转写用户录音，再复用文本 turn 流程。
     *
     * @param audioPath    Java 已落盘的用户录音绝对路径
     * @param userAudioUrl 前端可回放该录音的 URL
     */
    TurnResult audioTurn(
            Long userId,
            String sessionId,
            int turnId,
            String audioPath,
            String userAudioUrl,
            String scene,
            String clientTranscript,
            String practiceMode,
            String practiceTarget,
            String coachPersona,
            String preferredVoice
    );

    /**
     * Python /internal/turn 的响应载荷。
     *
     * @param aiReply      AI 文本回复
     * @param audioUrl     生成的音频 URL（可空，Python 侧异步生成时为空）
     * @param corrections  语法纠错 JSON 字符串
     * @param shadowAnswer 影子跟读参考句
     * @param abilityScore 能力分析 JSON 字符串
     * @param pronunciationScore 发音评测分数字符串
     * @param strategy     下一轮对话策略标识
     * @param summary      课后总结 JSON 字符串（仅结束分支有值）
     */
    record TurnResult(
            String aiReply,
            String audioUrl,
            String corrections,
            String shadowAnswer,
            String abilityScore,
            String pronunciationScore,
            String strategy,
            String summary,
            String transcript,
            String userAudioUrl,
            String speechMetrics,
            String wordTimestamps
    ) {}
}
