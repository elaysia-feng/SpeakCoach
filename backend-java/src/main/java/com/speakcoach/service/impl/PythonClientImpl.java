// PythonClientImpl — 通过 WebClient 调用内部 Python /internal/turn 端点。
// 按规范，硬件级失败（超时 / 5xx / 连接错误）会以 HTTP 502 Bad Gateway 的 ApiException
// 向上抛出，使上游调用方（TurnService）看到明确的信号，由其决定重试或降级。
// 成功响应会映射为 PythonClient.TurnResult 记录。
package com.speakcoach.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.speakcoach.exception.ApiException;
import com.speakcoach.service.PythonClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python AI 网关客户端实现
 * <p>所有调用走 {@link WebClient}，超时由 {@link com.speakcoach.config.WebClientConfig}
 * 配置的底层 Reactor Netty {@code HttpClient.responseTimeout} 控制；这里再加一个
 * {@code block(Duration)} 作为最后兜底，避免 block 永远不返回。</p>
 */
@Service
@Slf4j
public class PythonClientImpl implements PythonClient {

    // block() 兜底 timeout。必须 >= python.service.timeout-seconds (responseTimeout)，
    // 否则 block() 会先于 HttpClient.responseTimeout 抛出 TimeoutException,实际
    // 等不到 WebClient 配的 150s。
    private static final Duration BLOCK_TIMEOUT = Duration.ofSeconds(150);

    private final WebClient pythonWebClient;

    public PythonClientImpl(@Qualifier("pythonWebClient") WebClient pythonWebClient) {
        this.pythonWebClient = pythonWebClient;
    }

    @Override
    public TurnResult turn(Long userId, String sessionId, int turnId, String userText, String scene,
                           String coachPersona, String preferredVoice) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(userId));
        body.put("session_id", sessionId);
        body.put("turn_id", turnId);
        body.put("user_text", userText);
        body.put("scene", scene);
        body.put("coach_persona", coachPersona == null ? "warm_strict" : coachPersona);
        body.put("preferred_voice", preferredVoice == null ? "linqian_voice" : preferredVoice);

        try {
            JsonNode resp = pythonWebClient.post()
                    .uri("/internal/turn")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(BLOCK_TIMEOUT);

            if (resp == null) {
                log.warn("Python /internal/turn returned null body for userId={} sessionId={} turnId={}",
                        userId, sessionId, turnId);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "python_upstream_error",
                        "Python AI service returned empty body");
            }

            return toTurnResult(resp);
        } catch (ApiException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            log.warn("Python /internal/turn HTTP {} error body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "python_upstream_error",
                    "Python AI service error: " + ex.getStatusCode().value() + " " + ex.getMessage());
        } catch (Exception ex) {
            log.warn("Python /internal/turn call failed: {}", ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "python_upstream_error",
                    "Python AI service error: " + ex.getMessage());
        }
    }

    @Override
    public TurnResult audioTurn(
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
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", String.valueOf(userId));
        body.put("session_id", sessionId);
        body.put("turn_id", turnId);
        body.put("audio_path", audioPath);
        body.put("user_audio_url", userAudioUrl);
        body.put("scene", scene);
        body.put("client_transcript", clientTranscript);
        body.put("practice_mode", practiceMode == null ? "" : practiceMode);
        body.put("practice_target", practiceTarget == null ? "" : practiceTarget);
        body.put("coach_persona", coachPersona == null ? "warm_strict" : coachPersona);
        body.put("preferred_voice", preferredVoice == null ? "linqian_voice" : preferredVoice);

        try {
            JsonNode resp = pythonWebClient.post()
                    .uri("/internal/audio-turn")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(BLOCK_TIMEOUT);

            if (resp == null) {
                log.warn("Python /internal/audio-turn returned null body for userId={} sessionId={} turnId={}",
                        userId, sessionId, turnId);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "python_upstream_error",
                        "Python AI service returned empty body");
            }

            return toTurnResult(resp);
        } catch (ApiException ex) {
            throw ex;
        } catch (WebClientResponseException ex) {
            log.warn("Python /internal/audio-turn HTTP {} error body={}", ex.getStatusCode(), ex.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "python_upstream_error",
                    "Python AI service error: " + ex.getStatusCode().value() + " " + ex.getMessage());
        } catch (Exception ex) {
            log.warn("Python /internal/audio-turn call failed: {}", ex.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "python_upstream_error",
                    "Python AI service error: " + ex.getMessage());
        }
    }

    private TurnResult toTurnResult(JsonNode resp) {
        return new TurnResult(
                textOrNull(resp, "ai_reply"),
                textOrNull(resp, "audio_url"),
                jsonOrNull(resp, "corrections"),
                textOrNull(resp, "shadow_answer"),
                jsonOrNull(resp, "ability_score"),
                textOrNull(resp, "pronunciation_score"),
                textOrNull(resp, "strategy"),
                jsonOrNull(resp, "summary"),
                textOrNull(resp, "transcript"),
                textOrNull(resp, "user_audio_url"),
                jsonOrNull(resp, "speech_metrics"),
                jsonOrNull(resp, "word_timestamps")
        );
    }

    /** 从 JsonNode 中取出一个字符串字段；字段缺失或显式 JSON null 时返回 null。 */
    private String textOrNull(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        return v.isTextual() ? v.asText() : v.toString();
    }

    /** JSON 字段按原始形态保存；若 Python 返回已编码的 JSON 字符串，则保留其文本内容。 */
    private String jsonOrNull(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        JsonNode v = n.get(field);
        return v.isTextual() ? v.asText() : v.toString();
    }
}
