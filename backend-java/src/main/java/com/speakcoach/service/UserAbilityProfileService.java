// UserAbilityProfileService — 长期能力画像契约。
// 具体实现位于 {@code com.speakcoach.service.impl.UserAbilityProfileServiceImpl}。
// 采用接口优先的设计，使控制器（及测试）依赖契约而非具体类，
// 也为将来加入缓存或后台重算实现留出干净的扩展点。
package com.speakcoach.service;

import com.speakcoach.dto.AuthDtos;

/**
 * 用户能力画像服务接口
 * <p>封装单个用户长期能力画像的读取与部分更新：</p>
 * <ul>
 *   <li>getProfile：按 userId 查询，不存在时自动创建默认画像</li>
 *   <li>updateProfile：部分更新，null 字段保持原值；commonErrors 为字符串形式的 JSON</li>
 * </ul>
 */
public interface UserAbilityProfileService {

    /**
     * 返回指定用户的当前画像，不存在时自动创建默认画像。
     *
     * @param userId 已认证用户的 ID（不能为 null）
     * @return 用户的能力画像
     * @throws com.speakcoach.exception.ApiException 当 {@code userId} 为空时返回 401
     */
    AuthDtos.AbilityProfileResponse getProfile(Long userId);

    /**
     * 对用户画像进行部分更新。请求中的每个分数字段都是可选的 —— null 表示
     * "保持原值"。{@code commonErrors} 是原始 JSON 字符串：null 视为"不修改"，
     * 空字符串视为"清空"。
     *
     * @param userId  已认证用户的 ID
     * @param request 部分更新负载
     * @return 更新后的画像
     */
    AuthDtos.AbilityProfileResponse updateProfile(Long userId, AuthDtos.AbilityProfileUpdateRequest request);
}
