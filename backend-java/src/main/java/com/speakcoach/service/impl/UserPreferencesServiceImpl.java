// UserPreferencesServiceImpl — 当前用户偏好（coach_persona + preferred_voice）的读写实现。
// M1-B 引入。
package com.speakcoach.service.impl;

import com.speakcoach.dto.AuthDtos;
import com.speakcoach.entity.User;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.UserMapper;
import com.speakcoach.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserPreferencesServiceImpl implements UserPreferencesService {

    /** 与 frontend/src/components/PersonaPicker.tsx 的 4 张人设卡保持一致。 */
    private static final Set<String> VALID_PERSONAS = Set.of(
            "warm_strict",
            "friendly_tutor",
            "ielts_examiner",
            "patient_grandma"
    );

    /** 当前唯一支持的 TTS 音色。预留扩展位。 */
    private static final Set<String> VALID_VOICES = Set.of("linqian_voice");

    /** 数据库列 DEFAULT 与 VALID_PERSONAS 同步 —— 这里集中复述，方便排错。 */
    private static final String DEFAULT_PERSONA = "warm_strict";
    private static final String DEFAULT_VOICE = "linqian_voice";

    private final UserMapper userMapper;

    @Override
    @Transactional(readOnly = true)
    public AuthDtos.UserPreferencesResponse getPreferences(Long userId) {
        User user = loadUser(userId);
        return toResponse(user);
    }

    @Override
    @Transactional
    public AuthDtos.UserPreferencesResponse updatePreferences(Long userId, AuthDtos.UserPreferencesUpdateRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "Request body is required");
        }
        // 先校验入参：非 null 字段必须落在白名单内。
        String persona = request.coachPersona();
        String voice = request.preferredVoice();
        if (persona != null && !persona.isBlank() && !VALID_PERSONAS.contains(persona)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_persona",
                    "Unknown coach_persona '" + persona + "'. Valid: " + VALID_PERSONAS);
        }
        if (voice != null && !voice.isBlank() && !VALID_VOICES.contains(voice)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_voice",
                    "Unknown preferred_voice '" + voice + "'. Valid: " + VALID_VOICES);
        }

        // 保证 user 行存在（demo 用户必存在；新用户由 register 创建）。
        User user = loadUser(userId);

        // 部分更新 —— 把空串视为"不修改"，把 null 视为"不修改"。
        String effectivePersona = (persona == null || persona.isBlank()) ? null : persona;
        String effectiveVoice = (voice == null || voice.isBlank()) ? null : voice;
        userMapper.updatePreferences(userId, effectivePersona, effectiveVoice);

        // 重新读以拿到 DB 真实值。
        User fresh = userMapper.selectById(userId);
        log.info("Updated preferences for userId={} persona={} voice={}",
                userId,
                fresh == null ? null : fresh.getCoachPersona(),
                fresh == null ? null : fresh.getPreferredVoice());
        return toResponse(fresh);
    }

    private User loadUser(Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User not found");
        }
        return user;
    }

    private AuthDtos.UserPreferencesResponse toResponse(User user) {
        String persona = user.getCoachPersona();
        String voice = user.getPreferredVoice();
        // DB 旧记录可能为 NULL（迁移前注册的用户），用 DEFAULT 兜底以保证前端拿到稳定值。
        return new AuthDtos.UserPreferencesResponse(
                persona == null || persona.isBlank() ? DEFAULT_PERSONA : persona,
                voice == null || voice.isBlank() ? DEFAULT_VOICE : voice
        );
    }
}
