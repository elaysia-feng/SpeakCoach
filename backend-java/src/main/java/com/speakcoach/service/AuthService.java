// AuthService — 用户注册与登录契约。
// 实现层必须对密码进行 BCrypt 哈希并签发 JWT。
package com.speakcoach.service;

import com.speakcoach.dto.AuthDtos;

/**
 * 认证服务接口
 * <p>封装用户注册、登录与 JWT 颁发的业务抽象。具体实现位于
 * {@code com.speakcoach.service.impl.AuthServiceImpl}。</p>
 */
public interface AuthService {

    /**
     * 用户注册。
     *
     * @param req 注册请求（username + password）
     * @return 包含新用户 ID 和 JWT 的响应
     * @throws com.speakcoach.exception.ApiException 409 {@code username_taken} 当用户名已存在
     */
    AuthDtos.AuthResponse register(AuthDtos.RegisterRequest req);

    /**
     * 用户登录。失败原因（用户不存在 / 密码错误）统一返回 401
     * {@code invalid_credentials}，避免用户名枚举。
     *
     * @param req 登录请求（username + password）
     * @return 包含用户 ID 和 JWT 的响应
     * @throws com.speakcoach.exception.ApiException 401 {@code invalid_credentials}
     */
    AuthDtos.AuthResponse login(AuthDtos.LoginRequest req);

    /**
     * 发送邮箱验证码。
     *
     * <p>{@code type == "register"} 时不检查邮箱是否已注册；{@code type == "login"} 时
     * 邮箱必须已注册，否则返回 400。</p>
     *
     * <p>60 秒内重复发送会命中 {@code VerificationCodeService} 的冷却 key 并抛出
     * {@code BusinessException(400, "Please wait 60s before resending")}。</p>
     *
     * @param email 收件人邮箱
     * @param type  "register" 或 "login"
     */
    void sendVerificationCode(String email, String type);

    /**
     * 校验邮箱验证码并颁发临时凭证（5 分钟有效），用于 {@code /set-password} 完成注册。
     *
     * @param email 收件人邮箱
     * @param code  6 位数字验证码
     * @return verify-token（短时 JWT，subject=email，type=verify）
     * @throws com.speakcoach.common.exception.BusinessException 400 当验证码错误或已过期
     */
    String verifyCode(String email, String code);

    /**
     * 邮箱-OTP 注册流程的第二步：用户拿到 verify-token 后，调用此接口提交 username+password
     * 完成注册并立即签发正式 JWT。
     *
     * @param verifyToken 上一步返回的临时凭证
     * @param username    新用户名（需在 3-64 字符内）
     * @param password    新密码（需在 6-128 字符内）
     * @return 包含 userId、username、email、JWT 的响应
     * @throws com.speakcoach.common.exception.BusinessException 400 当 verify-token 无效或过期；409 当 username/email 已被占用
     */
    AuthDtos.AuthResponse setPassword(String verifyToken, String username, String password);

    /**
     * 邮箱 + 验证码登录。
     *
     * @param email 收件人邮箱
     * @param code  6 位数字验证码
     * @return 包含 userId、username、email、JWT 的响应
     * @throws com.speakcoach.common.exception.BusinessException 400 当验证码错误/过期或邮箱未注册
     */
    AuthDtos.AuthResponse loginByCode(String email, String code);

    /**
     * 解析当前已认证用户（{@code GET /api/auth/me}）。{@code userId} 由 {@code JwtAuthFilter}
     * 注入到 {@code SecurityContext} 的 {@code AuthPrincipal} 中，控制器负责从 principal 取出。
     *
     * <ul>
     *   <li>userId 为空 → 401 {@code unauthenticated}（JWT 校验已经过，理论上不会到这里）</li>
     *   <li>userId 存在但库中无该用户 → 404 {@code user_not_found}</li>
     * </ul>
     *
     * @param userId 来自 {@code AuthPrincipal.userId()} 的当前用户主键
     * @return 当前用户档案（不暴露密码哈希）
     */
    AuthDtos.CurrentUserResponse getCurrentUser(Long userId);
}
