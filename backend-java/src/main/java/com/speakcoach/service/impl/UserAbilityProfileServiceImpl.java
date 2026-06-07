// UserAbilityProfileServiceImpl — 读取与更新用户的长期能力画像。
// 若画像记录不存在（例如该用户在本表建立前已注册），读取时会按默认分值懒创建。
package com.speakcoach.service.impl;

import com.speakcoach.dto.AuthDtos;
import com.speakcoach.entity.UserAbilityProfile;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.UserAbilityProfileMapper;
import com.speakcoach.service.UserAbilityProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 用户能力画像服务实现
 * <ul>
 *   <li>getProfile：按 userId 查询，不存在时自动创建默认画像</li>
 *   <li>updateProfile：部分更新，null 字段保持原值；commonErrors 为字符串形式的 JSON</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAbilityProfileServiceImpl implements UserAbilityProfileService {

    /** 新建画像记录时使用的默认分值。 */
    private static final int DEFAULT_SCORE = 50;

    private final UserAbilityProfileMapper profileMapper;

    /**
     * 返回指定用户的当前画像，不存在时自动创建默认画像。
     *
     * @param userId 已认证用户的 ID（不能为 null）
     * @return 用户的能力画像
     * @throws ApiException 当 {@code userId} 缺失时返回 401
     */
    @Override
    @Transactional
    public AuthDtos.AbilityProfileResponse getProfile(Long userId) {
        requireUserId(userId);
        UserAbilityProfile profile = Optional.ofNullable(profileMapper.selectById(userId))
                .orElseGet(() -> createDefaultProfile(userId));
        return toResponse(profile);
    }

    /**
     * 对用户画像进行部分更新。请求中的每个分数字段都是可选的 —— null 表示
     * "保持原值"。{@code commonErrors} 是原始 JSON 字符串：null 视为"不修改"，
     * 空字符串视为"清空"。
     *
     * @param userId  已认证用户的 ID
     * @param request 部分更新负载
     * @return 更新后的画像
     */
    @Override
    @Transactional
    public AuthDtos.AbilityProfileResponse updateProfile(Long userId, AuthDtos.AbilityProfileUpdateRequest request) {
        requireUserId(userId);
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "Request body is required");
        }

        UserAbilityProfile profile = Optional.ofNullable(profileMapper.selectById(userId))
                .orElseGet(() -> createDefaultProfile(userId));

        if (request.grammarScore() != null) {
            profile.setGrammarScore(request.grammarScore());
        }
        if (request.vocabularyScore() != null) {
            profile.setVocabularyScore(request.vocabularyScore());
        }
        if (request.fluencyScore() != null) {
            profile.setFluencyScore(request.fluencyScore());
        }
        if (request.logicScore() != null) {
            profile.setLogicScore(request.logicScore());
        }
        if (request.commonErrors() != null) {
            // 空字符串视为"清空列表"；否则原样存储。此处刻意不再解析/校验 JSON 结构
            // —— 实体列为 json 类型，schema 的真值由前端负责。
            String trimmed = request.commonErrors().trim();
            profile.setCommonErrors(trimmed.isEmpty() ? "[]" : trimmed);
        }

        UserAbilityProfile saved = profileMapper.updateById(profile) > 0
                ? profile
                : profile;
        log.info("Updated ability profile for userId={}", userId);
        return toResponse(saved);
    }

    private UserAbilityProfile createDefaultProfile(Long userId) {
        UserAbilityProfile fresh = UserAbilityProfile.builder()
                .userId(userId)
                .grammarScore(DEFAULT_SCORE)
                .vocabularyScore(DEFAULT_SCORE)
                .fluencyScore(DEFAULT_SCORE)
                .logicScore(DEFAULT_SCORE)
                .commonErrors("[]")
                .build();
        profileMapper.insert(fresh);
        return fresh;
    }

    private AuthDtos.AbilityProfileResponse toResponse(UserAbilityProfile profile) {
        return new AuthDtos.AbilityProfileResponse(
                profile.getUserId(),
                profile.getGrammarScore(),
                profile.getVocabularyScore(),
                profile.getFluencyScore(),
                profile.getLogicScore(),
                profile.getCommonErrors()
        );
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
    }
}
