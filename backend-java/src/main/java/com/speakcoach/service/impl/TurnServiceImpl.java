// TurnServiceImpl — 编排单次 chat 回合：加载会话、调用 Python、持久化回合与审计。
package com.speakcoach.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.speakcoach.config.TtsProperties;
import com.speakcoach.dto.ChatDtos;
import com.speakcoach.entity.SpeakingSession;
import com.speakcoach.entity.SpeakingTurn;
import com.speakcoach.entity.User;
import com.speakcoach.entity.UserAbilityProfile;
import com.speakcoach.mapper.SpeakingSessionMapper;
import com.speakcoach.mapper.SpeakingTurnMapper;
import com.speakcoach.mapper.UserAbilityProfileMapper;
import com.speakcoach.mapper.UserMapper;
import com.speakcoach.service.AuditService;
import com.speakcoach.service.OssStorageService;
import com.speakcoach.service.PythonClient;
import com.speakcoach.service.SessionService;
import com.speakcoach.service.TurnService;
import com.speakcoach.service.UserAbilityHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.speakcoach.exception.ApiException;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话回合服务实现
 *
 * <p>编排一次 chat 回合的完整流程：</p>
 * <ol>
 *   <li>调用 {@code SessionService.getForUser} 校验归属（404/403 由 service 抛出）</li>
 *   <li>计算下一回合 turnId — 见 {@link #nextTurnId}</li>
 *   <li>自增 session.turn_count 并 save（managed-entity dirty-checking）</li>
 *   <li>调用 Python 内部 {@code /internal/turn} 接口</li>
 *   <li>把对话持久化到 speaking_turn</li>
 *   <li>写一条 audit_log（失败被 try/catch 吞掉，绝不破坏用户主流程）</li>
 *   <li>返回 ChatResponse</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TurnServiceImpl implements TurnService {

    private final SessionService sessionService;
    private final SpeakingSessionMapper sessionMapper;
    private final SpeakingTurnMapper turnMapper;
    private final PythonClient pythonClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final OssStorageService ossStorageService;
    private final TtsProperties ttsProperties;
    private final UserMapper userMapper;
    private final UserAbilityProfileMapper userAbilityProfileMapper;
    private final UserAbilityHistoryService userAbilityHistoryService;

    @Override
    public ChatDtos.ChatResponse handleTurn(Long userId, ChatDtos.ChatRequest request) {
        TurnReservation reservation = transactionTemplate.execute(status -> reserveTurn(userId, request.sessionId()));
        if (reservation == null) {
            throw new IllegalStateException("Failed to reserve chat turn");
        }

        // M1-B: 加载当前用户的偏好（教练人格 + TTS 音色），透传给 Python。
        UserPreferences prefs = loadUserPreferences(userId);

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("user_id", userId);
        inputPayload.put("session_id", reservation.sessionId());
        inputPayload.put("turn_id", reservation.turnId());
        inputPayload.put("user_text", request.userText());
        inputPayload.put("scene", reservation.scene());
        inputPayload.put("coach_persona", prefs.coachPersona());
        inputPayload.put("preferred_voice", prefs.preferredVoice());
        String inputJson = toJson(inputPayload);

        long start = System.currentTimeMillis();
        // 外部 AI 调用保持在数据库事务之外。
        PythonClient.TurnResult result = pythonClient.turn(
                userId,
                reservation.sessionId(),
                reservation.turnId(),
                request.userText(),
                reservation.scene(),
                prefs.coachPersona(),
                prefs.preferredVoice()
        );
        long latencyMs = System.currentTimeMillis() - start;
        PythonClient.TurnResult ossResult = withOssAiAudio(userId, reservation, result);

        transactionTemplate.executeWithoutResult(status -> persistTurn(
                userId,
                resolvedUserText(request.userText(), ossResult),
                null,
                reservation,
                ossResult
        ));
        result = ossResult;

        // 审计 —— AuditServiceImpl 已经会吞掉异常，这里再次 try/catch
        //     是为了防止未来替换 audit 实现时回退这层保障。
        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("ai_reply", result.aiReply());
        outputPayload.put("audio_url", result.audioUrl());
        outputPayload.put("corrections", result.corrections());
        outputPayload.put("shadow_answer", result.shadowAnswer());
        outputPayload.put("ability_score", result.abilityScore());
        outputPayload.put("pronunciation_score", result.pronunciationScore());
        outputPayload.put("strategy", result.strategy());
        outputPayload.put("summary", result.summary());
        String outputJson = toJson(outputPayload);

        try {
            auditService.record(
                    userId,
                    reservation.sessionId(),
                    reservation.turnId(),
                    "chat_orchestrator",
                    inputJson,
                    outputJson,
                    latencyMs,
                    "java",
                    null
            );
        } catch (Exception ex) {
            // 纵深防御：绝不让审计失败破坏用户主流程。
            log.warn("audit_service.record threw unexpectedly (node=chat_orchestrator): {}", ex.getMessage());
        }

        // M1-A: 在每次 turn 之后写入能力画像快照。UserAbilityHistoryServiceImpl
        //       自身会吞掉所有异常，所以这里不需要 try/catch —— 但为防未来
        //       该服务被替换成非防御性实现，再做一层纵深防御。
        try {
            UserAbilityProfile profile = userAbilityProfileMapper.selectById(userId);
            userAbilityHistoryService.snapshot(
                    userId,
                    reservation.sessionId(),
                    reservation.turnId(),
                    profile
            );
        } catch (Exception ex) {
            log.warn("user_ability_history.snapshot threw unexpectedly (userId={}): {}", userId, ex.getMessage());
        }

        return new ChatDtos.ChatResponse(
                reservation.turnId(),
                resolvedUserText(request.userText(), result),
                result.userAudioUrl(),
                result.aiReply(),
                result.audioUrl(),
                result.corrections(),
                result.shadowAnswer(),
                result.abilityScore(),
                result.pronunciationScore(),
                result.strategy(),
                result.summary(),
                result.speechMetrics(),
                result.wordTimestamps()
        );
    }

    @Override
    public ChatDtos.ChatResponse handleAudioTurn(
            Long userId,
            String sessionId,
            MultipartFile audio,
            String clientTranscript,
            String practiceMode,
            String practiceTarget
    ) {
        if (audio == null || audio.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "audio_required", "Audio file is required");
        }
        TurnReservation reservation = transactionTemplate.execute(status -> reserveTurn(userId, sessionId));
        if (reservation == null) {
            throw new IllegalStateException("Failed to reserve audio turn");
        }

        // M1-B: 加载当前用户的偏好（教练人格 + TTS 音色），透传给 Python。
        UserPreferences prefs = loadUserPreferences(userId);

        StoredAudio stored = storeUserAudio(userId, reservation.sessionId(), reservation.turnId(), audio);

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("user_id", userId);
        inputPayload.put("session_id", reservation.sessionId());
        inputPayload.put("turn_id", reservation.turnId());
        inputPayload.put("audio_url", stored.url());
        inputPayload.put("scene", reservation.scene());
        inputPayload.put("practice_mode", practiceMode);
        inputPayload.put("coach_persona", prefs.coachPersona());
        inputPayload.put("preferred_voice", prefs.preferredVoice());
        String inputJson = toJson(inputPayload);

        long start = System.currentTimeMillis();
        PythonClient.TurnResult result;
        long latencyMs;
        try {
            result = pythonClient.audioTurn(
                    userId,
                    reservation.sessionId(),
                    reservation.turnId(),
                    stored.path().toString(),
                    stored.url(),
                    reservation.scene(),
                    clientTranscript,
                    practiceMode,
                    practiceTarget,
                    prefs.coachPersona(),
                    prefs.preferredVoice()
            );
            latencyMs = System.currentTimeMillis() - start;
        } finally {
            deleteTempAudio(stored.path());
        }
        PythonClient.TurnResult ossResult = withOssAiAudio(userId, reservation, result);

        String transcript = resolvedUserText(clientTranscript, ossResult);
        if (transcript.isBlank()) {
            transcript = "Voice transcript unavailable";
        }
        String userTranscript = transcript;
        transactionTemplate.executeWithoutResult(status -> persistTurn(
                userId,
                userTranscript,
                stored.url(),
                reservation,
                ossResult
        ));
        result = ossResult;

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put("transcript", transcript);
        outputPayload.put("user_audio_url", stored.url());
        outputPayload.put("ai_reply", result.aiReply());
        outputPayload.put("audio_url", result.audioUrl());
        outputPayload.put("ability_score", result.abilityScore());
        outputPayload.put("speech_metrics", result.speechMetrics());
        outputPayload.put("word_timestamps", result.wordTimestamps());
        String outputJson = toJson(outputPayload);

        try {
            auditService.record(
                    userId,
                    reservation.sessionId(),
                    reservation.turnId(),
                    "audio_chat_orchestrator",
                    inputJson,
                    outputJson,
                    latencyMs,
                    "java",
                    null
            );
        } catch (Exception ex) {
            log.warn("audit_service.record threw unexpectedly (node=audio_chat_orchestrator): {}", ex.getMessage());
        }

        return new ChatDtos.ChatResponse(
                reservation.turnId(),
                transcript,
                stored.url(),
                result.aiReply(),
                result.audioUrl(),
                result.corrections(),
                result.shadowAnswer(),
                result.abilityScore(),
                result.pronunciationScore(),
                result.strategy(),
                result.summary(),
                result.speechMetrics(),
                result.wordTimestamps()
        );
    }

    @Override
    public List<ChatDtos.ChatHistoryItem> listHistory(Long userId, String sessionId) {
        sessionService.getForUser(sessionId, userId);
        return turnMapper.selectBySessionIdOrderByTurnIdAsc(sessionId).stream()
                .map(turn -> new ChatDtos.ChatHistoryItem(
                        turn.getTurnId(),
                        turn.getUserText(),
                        turn.getUserAudioUrl(),
                        turn.getAiReply(),
                        turn.getAudioUrl(),
                        turn.getCorrections(),
                        turn.getShadowAnswer(),
                        turn.getAbilityScore(),
                        null,
                        turn.getStrategy(),
                        null,
                        turn.getSpeechMetrics(),
                        turn.getWordTimestamps()
                ))
                .toList();
    }

    private TurnReservation reserveTurn(Long userId, String sessionId) {
        // 归属权校验，沿用既有的 404/403 行为。
        SpeakingSession session = sessionMapper.selectByIdAndUserIdForUpdate(sessionId, userId);
        if (session == null) {
            // 保留既有 404/403 语义，同时确保后续一定运行在 FOR UPDATE 锁保护下。
            sessionService.getForUser(sessionId, userId);
            throw new IllegalStateException("Failed to lock chat session for turn reservation");
        }

        int turnId = (session.getTurnCount() == null ? 0 : session.getTurnCount()) + 1;
        session.setTurnCount(turnId);
        session.setUpdatedAt(java.time.LocalDateTime.now());
        sessionMapper.updateById(session);

        return new TurnReservation(session.getId(), session.getScene(), turnId);
    }

    private void persistTurn(
            Long userId,
            String userText,
            String userAudioUrl,
            TurnReservation reservation,
            PythonClient.TurnResult result
    ) {
        // JSON 列不接受空字符串，因此将空负载强制转为 "{}"。
        SpeakingTurn turn = SpeakingTurn.builder()
                .userId(userId)
                .sessionId(reservation.sessionId())
                .turnId(reservation.turnId())
                .userText(userText)
                .userAudioUrl(userAudioUrl)
                .aiReply(result.aiReply())
                .audioUrl(result.audioUrl())
                .corrections(jsonOrEmptyObject(result.corrections()))
                .shadowAnswer(result.shadowAnswer())
                .abilityScore(jsonOrEmptyObject(result.abilityScore()))
                .speechMetrics(jsonOrEmptyObject(result.speechMetrics()))
                .wordTimestamps(jsonOrEmptyArray(result.wordTimestamps()))
                .strategy(result.strategy())
                .checkpointId(null)
                .status("active")
                .build();
        turnMapper.insert(turn);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException ex) {
            log.warn("JSON serialization failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * 把本应承载 JSON 文档的字符串规整为适合 MySQL {@code JSON} 列的非空值。
     * {@code null} 或空串会被替换为 {@code "{}"}，确保总能写入。
     * 合法的 JSON 原样透传；非法 JSON 仅记日志并替换为安全默认值，
     * 不会让整次 turn 失败。
     */
    private String jsonOrEmptyObject(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "{}";
        }
        String trimmed = candidate.trim();
        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (JsonProcessingException ex) {
            log.warn("Discarding invalid JSON payload for speaking_turn: {}",
                    trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
            return "{}";
        }
    }

    private String jsonOrEmptyArray(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "[]";
        }
        String trimmed = candidate.trim();
        try {
            objectMapper.readTree(trimmed);
            return trimmed;
        } catch (JsonProcessingException ex) {
            log.warn("Discarding invalid JSON array payload for speaking_turn: {}",
                    trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed);
            return "[]";
        }
    }

    private String resolvedUserText(String fallback, PythonClient.TurnResult result) {
        if (result != null && result.transcript() != null && !result.transcript().isBlank()) {
            return result.transcript();
        }
        return fallback == null ? "" : fallback;
    }

    private StoredAudio storeUserAudio(Long userId, String sessionId, int turnId, MultipartFile audio) {
        String extension = audioExtension(audio.getOriginalFilename(), audio.getContentType());
        String filename = "user_turn_" + turnId + extension;
        Path target;
        try {
            target = Files.createTempFile("speakcoach-user-" + userId + "-" + turnId + "-", extension);
            audio.transferTo(target);
        } catch (IOException ex) {
            log.warn("Failed to store user audio: {}", ex.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "audio_store_failed", "Failed to store audio");
        }
        String objectKey = "audio/" + userId + "/" + sessionId + "/" + filename;
        String url = ossStorageService.uploadAudio(target, objectKey, audio.getContentType());
        return new StoredAudio(target, url);
    }

    private void deleteTempAudio(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.debug("Failed to delete temp audio {}: {}", path, ex.getMessage());
        }
    }

    private PythonClient.TurnResult withOssAiAudio(
            Long userId,
            TurnReservation reservation,
            PythonClient.TurnResult result
    ) {
        if (result == null || result.audioUrl() == null || result.audioUrl().isBlank()) {
            return result;
        }
        Path localAudio = localAiAudioPath(result.audioUrl());
        if (localAudio == null) {
            return result;
        }
        if (!Files.exists(localAudio) || !Files.isRegularFile(localAudio)) {
            log.debug("AI audio local file not found, keep original url: {}", result.audioUrl());
            return result;
        }

        String objectKey = "audio/" + userId + "/" + reservation.sessionId()
                + "/ai_turn_" + reservation.turnId() + audioExtension(localAudio.getFileName().toString(), "audio/wav");
        String ossUrl = ossStorageService.uploadAudio(localAudio, objectKey, contentTypeForAudio(localAudio));
        return new PythonClient.TurnResult(
                result.aiReply(),
                ossUrl,
                result.corrections(),
                result.shadowAnswer(),
                result.abilityScore(),
                result.pronunciationScore(),
                result.strategy(),
                result.summary(),
                result.transcript(),
                result.userAudioUrl(),
                result.speechMetrics(),
                result.wordTimestamps()
        );
    }

    private Path localAiAudioPath(String audioUrl) {
        String baseUrl = ttsProperties.getAudioBaseUrl() == null || ttsProperties.getAudioBaseUrl().isBlank()
                ? "/api/audio"
                : ttsProperties.getAudioBaseUrl();
        String normalizedBase = trimTrailingSlash(baseUrl);
        String normalizedUrl = audioUrl.trim();
        if (normalizedUrl.startsWith("http://") || normalizedUrl.startsWith("https://")) {
            return null;
        }
        if (!normalizedUrl.startsWith(normalizedBase + "/")) {
            return null;
        }
        String relative = normalizedUrl.substring((normalizedBase + "/").length());
        String[] parts = relative.split("/");
        if (parts.length != 3) {
            return null;
        }
        String userSegment = decodeUrlPart(parts[0]);
        String sessionSegment = decodeUrlPart(parts[1]);
        String filename = decodeUrlPart(parts[2]);
        if (userSegment.contains("..") || sessionSegment.contains("..") || filename.contains("..")
                || filename.contains("\\") || filename.contains("/")) {
            return null;
        }
        Path storageRoot = Paths.get(ttsProperties.getAudioStoragePath() == null
                        ? "./storage/audio"
                        : ttsProperties.getAudioStoragePath())
                .toAbsolutePath()
                .normalize();
        Path target = storageRoot.resolve(userSegment).resolve(sessionSegment).resolve(filename).normalize();
        return target.startsWith(storageRoot) ? target : null;
    }

    private String contentTypeForAudio(Path audio) {
        String name = audio.getFileName().toString().toLowerCase();
        if (name.endsWith(".webm")) return "audio/webm";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".ogg")) return "audio/ogg";
        return "audio/wav";
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String decodeUrlPart(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String audioExtension(String originalFilename, String contentType) {
        String name = originalFilename == null ? "" : originalFilename.toLowerCase();
        if (name.endsWith(".wav")) return ".wav";
        if (name.endsWith(".mp3")) return ".mp3";
        if (name.endsWith(".m4a")) return ".m4a";
        if (name.endsWith(".ogg")) return ".ogg";
        if (name.endsWith(".webm")) return ".webm";
        String type = contentType == null ? "" : contentType.toLowerCase();
        if (type.contains("wav")) return ".wav";
        if (type.contains("mpeg") || type.contains("mp3")) return ".mp3";
        if (type.contains("mp4") || type.contains("m4a")) return ".m4a";
        if (type.contains("ogg")) return ".ogg";
        return ".webm";
    }

    private record TurnReservation(String sessionId, String scene, int turnId) {}

    private record StoredAudio(Path path, String url) {}

    /**
     * M1-B: 在调 Python 之前从 user 表读偏好。任何失败（user 不存在 / 列 NULL）
     * 都回退到 DEFAULT，绝不让偏好读取阻断 chat 主链路 —— 这与 python
     * 侧把缺省值放在 Pydantic 模型默认里的策略保持一致。
     */
    private UserPreferences loadUserPreferences(Long userId) {
        try {
            User user = userMapper.selectById(userId);
            if (user == null) {
                return UserPreferences.defaults();
            }
            String persona = user.getCoachPersona();
            String voice = user.getPreferredVoice();
            return new UserPreferences(
                    persona == null || persona.isBlank() ? "warm_strict" : persona,
                    voice == null || voice.isBlank() ? "linqian_voice" : voice
            );
        } catch (Exception ex) {
            log.debug("Failed to read user preferences for userId={}, falling back to defaults: {}",
                    userId, ex.getMessage());
            return UserPreferences.defaults();
        }
    }

    private record UserPreferences(String coachPersona, String preferredVoice) {
        static UserPreferences defaults() {
            return new UserPreferences("warm_strict", "linqian_voice");
        }
    }
}
