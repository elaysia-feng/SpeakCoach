// UserPreferencesService — 当前用户偏好（coach_persona + preferred_voice）的读写。
// M1-B 引入。
package com.speakcoach.service;

import com.speakcoach.dto.AuthDtos;

public interface UserPreferencesService {

    /** 读取当前用户的偏好。缺失 user 行时抛出 401。 */
    AuthDtos.UserPreferencesResponse getPreferences(Long userId);

    /** 部分更新当前用户的偏好。null 字段保留原值。 */
    AuthDtos.UserPreferencesResponse updatePreferences(Long userId, AuthDtos.UserPreferencesUpdateRequest request);
}
