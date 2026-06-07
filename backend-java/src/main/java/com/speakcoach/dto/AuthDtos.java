// 认证 + 用户画像端点（register / login / me / profile）的 DTO 记录。
package com.speakcoach.dto;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    private AuthDtos() {}

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 6, max = 128) String password,
            @NotBlank @Email @Size(max = 255) String email
    ) {}

    /**
     * 登录请求接受 {@code username} 或 {@code email}（不能两者同时为空）。
     * 此处刻意省略 {@code loginMode} 字段：基于验证码的登录由独立的
     * {@code /api/auth/login-by-code} 端点提供，与 AI-Resume-Forge 的契约保持一致。
     */
    public record LoginRequest(
            String username,
            @Email String email,
            @NotBlank String password
    ) {}

    /**
     * 由 /api/auth/register 与 /api/auth/login 返回。一并携带 {@code username} 与
     * {@code email}，便于前端在无需额外 /me 往返的情况下填充用户状态。
     */
    public record AuthResponse(
            Long userId,
            String username,
            String email,
            String token
    ) {}

    /**
     * 由 /api/auth/me 返回，反映从当前请求 JWT 中解析出的 principal —— 永不暴露密码哈希。
     */
    public record CurrentUserResponse(
            Long userId,
            String username,
            String email
    ) {}

    /**
     * {@code POST /api/auth/send-code} 的请求体。{@code type} 选择流程上下文：
     * {@code "register"}（默认，对邮箱是否存在不敏感）或
     * {@code "login"}（对未注册邮箱返回 400）。
     */
    public record SendCodeRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @Pattern(regexp = "register|login", message = "type must be 'register' or 'login'") String type
    ) {}

    /**
     * {@code POST /api/auth/verify-code} 与
     * {@code POST /api/auth/login-by-code} 的请求体。
     */
    public record VerifyCodeRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 6, max = 6) String code
    ) {}

    /**
     * {@code POST /api/auth/set-password} 的请求体（邮箱-OTP 注册流程的第二步，
     * 紧随 {@code /verify-code} 之后）。
     */
    public record SetPasswordRequest(
            @NotBlank String verifyToken,
            @NotBlank @Size(min = 3, max = 64) String username,
            @NotBlank @Size(min = 6, max = 128) String password
    ) {}

    /**
     * {@code POST /api/auth/verify-code} 的响应。{@code verifyToken} 是带
     * {@code type=verify} 声明的短时效（5 分钟）JWT，{@code /set-password} 必须回传。
     */
    public record VerifyCodeResponse(
            String verifyToken
    ) {}

    /**
     * GET /api/users/me/profile 返回的长期能力画像。{@code commonErrors} 为
     * JSON 编码的字符串（与实体上的 {@code @JdbcTypeCode(SqlTypes.JSON)} 列匹配），
     * 由前端自行决定反序列化方式。
     */
    public record AbilityProfileResponse(
            Long userId,
            Integer grammarScore,
            Integer vocabularyScore,
            Integer fluencyScore,
            Integer logicScore,
            String commonErrors
    ) {}

    /**
     * PUT /api/users/me/profile 的请求体。所有分数字段都是可选 —— null 表示
     * "保留原值"，从而支持部分更新。
     */
    public record AbilityProfileUpdateRequest(
            @Min(0) @Max(100) Integer grammarScore,
            @Min(0) @Max(100) Integer vocabularyScore,
            @Min(0) @Max(100) Integer fluencyScore,
            @Min(0) @Max(100) Integer logicScore,
            String commonErrors
    ) {}

    /**
     * GET /api/users/me/preferences 的响应体。
     * {@code coachPersona} 形如 {@code warm_strict} / {@code friendly_tutor} /
     * {@code ielts_examiner} / {@code patient_grandma}；{@code preferredVoice} 当前固定为
     * {@code linqian_voice}，保留扩展位（M1-B）。
     */
    public record UserPreferencesResponse(
            String coachPersona,
            String preferredVoice
    ) {}

    /**
     * PUT /api/users/me/preferences 的请求体。任一字段为 null 表示保留原值。
     * M1-B 引入。
     */
    public record UserPreferencesUpdateRequest(
            @Size(max = 32) String coachPersona,
            @Size(max = 64) String preferredVoice
    ) {}

    // =========================================================================
    // M1-A 长期记忆里程碑：能力历史快照
    // =========================================================================

    /**
     * GET /api/users/me/profile/timeline 的响应 —— 按时间倒序排列的
     * {@link AbilityHistoryEntry} 列表（默认最多 10 条）。
     */
    public record AbilityHistoryResponse(
            Long userId,
            int count,
            List<AbilityHistoryEntry> entries
    ) {}

    /**
     * 单条能力历史快照的展示项 —— 把 entity 中的 JSON 字段展开为 4 维分数，
     * 方便前端直接渲染 sparkline。{@code snapshotJson} 仍然保留以便前端
     * 在需要时自行反序列化原始负载。
     */
    public record AbilityHistoryEntry(
            Long id,
            String sessionId,
            Integer turnId,
            Integer grammarScore,
            Integer vocabularyScore,
            Integer fluencyScore,
            Integer logicScore,
            String commonErrors,
            String cefrLevel,
            String snapshotJson,
            String createdAt
    ) {}

    /**
     * POST /api/internal/profile/snapshot 的请求体 —— Python 侧在 save_report
     * 节点把本轮 ability_score 推到 Java 时使用。
     */
    public record AbilityHistorySnapshotRequest(
            Long userId,
            String sessionId,
            Integer turnId,
            Integer grammarScore,
            Integer vocabularyScore,
            Integer fluencyScore,
            Integer logicScore,
            String commonErrors
    ) {}
}
