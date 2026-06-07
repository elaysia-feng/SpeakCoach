// AuthServiceImpl — 用户注册与登录，BCrypt 密码哈希，JWT 签发。
// 登录路径沿用 Luvia AuthServiceImpl 的恒定时间模式，避免基于响应时序的用户名枚举。
//
// 邮箱-OTP 注册流程（与 AI-Resume-Forge 保持一致）：
//   1. POST /api/auth/send-code    { email, type=register }   → 200（通过 QQ SMTP 发送邮件）
//   2. POST /api/auth/verify-code  { email, code }            → 200 { verifyToken }
//   3. POST /api/auth/set-password { verifyToken, username, password } → 200 { token }
//
// 邮箱-OTP 登录流程：
//   1. POST /api/auth/send-code    { email, type=login }      → 200
//   2. POST /api/auth/login-by-code { email, code }           → 200 { token }
package com.speakcoach.service.impl;

import com.speakcoach.common.exception.BusinessException;
import com.speakcoach.dto.AuthDtos;
import com.speakcoach.entity.User;
import com.speakcoach.entity.UserAbilityProfile;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.UserAbilityProfileMapper;
import com.speakcoach.mapper.UserMapper;
import com.speakcoach.security.JwtUtil;
import com.speakcoach.service.AuthService;
import com.speakcoach.service.EmailService;
import com.speakcoach.service.VerificationCodeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * 认证服务实现
 * <ul>
 *   <li>注册：BCrypt 加密 → 写库 → 创建空的 ability profile → 颁发 JWT</li>
 *   <li>登录：先用 dummy hash 做一次恒定时间密码校验（无论用户是否存在都执行），再查库比对，
 *       避免响应时间差异泄漏用户名是否存在</li>
 *   <li>getCurrentUser：根据 userId 解析当前用户，找不到时返回 404</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthServiceImpl implements AuthService {

    /** 用于在用户不存在时保持登录响应时间恒定的虚拟 BCrypt 哈希。 */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    /** 新建画像记录时使用的默认分值。 */
    private static final int DEFAULT_SCORE = 50;

    private final UserMapper userMapper;
    private final UserAbilityProfileMapper profileMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final VerificationCodeService verificationCodeService;

    @Override
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest req) {
        String email = normalizeEmail(req.email());
        // 在访问数据库前进行防御性唯一性检查 —— 即便并发场景绕过唯一索引，
        // 也能返回清晰的 409 提示。
        if (userMapper.existsByUsername(req.username())) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken", "Username already exists");
        }
        if (userMapper.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "Email already exists");
        }

        User user = User.builder()
                .username(req.username())
                .email(email)
                .passwordHash(passwordEncoder.encode(req.password()))
                .build();
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException ex) {
            // 并发兜底。不去区分冲突的是 username 还是 email —— 直接返回 409，
            // 由调用方按需重新校验其关心的字段。
            throw new ApiException(HttpStatus.CONFLICT, "duplicate_user", "Username or email already exists");
        }

        // 数据库自增 id 现已由 MP 回填到实体上。
        Long userId = user.getId();

        // 初始化一份空的能力画像，使下游服务可以立即读取分数。
        UserAbilityProfile profile = UserAbilityProfile.builder()
                .userId(userId)
                .grammarScore(DEFAULT_SCORE)
                .vocabularyScore(DEFAULT_SCORE)
                .fluencyScore(DEFAULT_SCORE)
                .logicScore(DEFAULT_SCORE)
                .commonErrors("[]")
                .build();
        profileMapper.insert(profile);

        log.info("Registered new user userId={} username={} email={}", userId, req.username(), email);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new AuthDtos.AuthResponse(user.getId(), user.getUsername(), user.getEmail(), token);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest req) {
        // 恒定时间探测，使响应时间不暴露用户名是否存在。
        passwordEncoder.matches(
                req.password() != null ? req.password() : "",
                DUMMY_BCRYPT_HASH
        );

        // 接受 username 或 email（两者不能同时为空）。selectByUsernameOrEmail
        // 将 AI-Resume-Forge "先 username 再 email" 的模式合并为单条查询。
        String username = req.username();
        String email = normalizeEmail(req.email());
        if ((username == null || username.isBlank()) && (email == null || email.isBlank())) {
            throw invalidCredentials();
        }
        User user = Optional.ofNullable(userMapper.selectByUsernameOrEmail(username, email))
                .orElseThrow(this::invalidCredentials);

        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new AuthDtos.AuthResponse(user.getId(), user.getUsername(), user.getEmail(), token);
    }

    @Override
    public void sendVerificationCode(String email, String type) {
        if (email == null || email.isBlank()) {
            throw BusinessException.badRequest("Email is required");
        }
        String normalizedEmail = normalizeEmail(email);
        String mode = type == null || type.isBlank() ? "register" : type;

        if ("login".equalsIgnoreCase(mode)) {
            // 登录模式的验证码只能发送给已注册的邮箱。
            if (userMapper.selectByEmail(normalizedEmail) == null) {
                throw BusinessException.badRequest("Email not registered");
            }
        }

        // 60 秒重发冷却 —— 用友好的 400 短路返回，而不是静默覆盖 Redis 中已有的验证码。
        if (verificationCodeService.isInCooldown(normalizedEmail)) {
            throw BusinessException.badRequest("Please wait 60s before resending");
        }

        String code = verificationCodeService.issueCode(normalizedEmail);
        emailService.sendVerificationCode(normalizedEmail, code);
        log.info("[AuthService] sent {} code to {}", mode, normalizedEmail);
    }

    @Override
    public String verifyCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        if (!verificationCodeService.verifyAndConsume(normalizedEmail, code)) {
            throw BusinessException.badRequest("Invalid or expired verification code");
        }
        String verifyToken = jwtUtil.generateVerifyToken(normalizedEmail);
        log.info("[AuthService] code verified for email={}", normalizedEmail);
        return verifyToken;
    }

    @Override
    @Transactional
    public AuthDtos.AuthResponse setPassword(String verifyToken, String username, String password) {
        String email;
        try {
            email = jwtUtil.getEmailFromVerifyToken(verifyToken);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("Invalid or expired verification token");
        }

        if (userMapper.existsByUsername(username)) {
            throw BusinessException.conflict("Username already exists");
        }
        if (userMapper.existsByEmail(email)) {
            throw BusinessException.conflict("Email already registered");
        }

        User user = User.builder()
                .username(username)
                .email(email)
                .passwordHash(passwordEncoder.encode(password))
                .build();
        try {
            userMapper.insert(user);
        } catch (DataIntegrityViolationException ex) {
            throw BusinessException.conflict("Username or email already exists");
        }

        // 数据库自增 id 现已由 MP 回填到实体上。
        Long userId = user.getId();

        UserAbilityProfile profile = UserAbilityProfile.builder()
                .userId(userId)
                .grammarScore(DEFAULT_SCORE)
                .vocabularyScore(DEFAULT_SCORE)
                .fluencyScore(DEFAULT_SCORE)
                .logicScore(DEFAULT_SCORE)
                .commonErrors("[]")
                .build();
        profileMapper.insert(profile);

        log.info("[AuthService] OTP-registered userId={} username={} email={}", userId, username, email);
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return new AuthDtos.AuthResponse(user.getId(), user.getUsername(), user.getEmail(), token);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse loginByCode(String email, String code) {
        String normalizedEmail = normalizeEmail(email);
        if (!verificationCodeService.verifyAndConsume(normalizedEmail, code)) {
            throw BusinessException.badRequest("Invalid or expired verification code");
        }
        User user = Optional.ofNullable(userMapper.selectByEmail(normalizedEmail))
                .orElseThrow(() -> BusinessException.badRequest("Email not registered"));
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        log.info("[AuthService] code-login userId={} email={}", user.getId(), normalizedEmail);
        return new AuthDtos.AuthResponse(user.getId(), user.getUsername(), user.getEmail(), token);
    }

    /**
     * 为 {@code GET /api/auth/me} 解析 principal。{@code userId} 来自
     * {@code JwtAuthFilter} 填充的 {@code AuthPrincipal}。用户不存在时返回 404
     * 而非 401，因为 JWT 自身已经在上游完成校验。
     */
    @Override
    @Transactional(readOnly = true)
    public AuthDtos.CurrentUserResponse getCurrentUser(Long userId) {
        if (userId == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required");
        }
        User user = Optional.ofNullable(userMapper.selectById(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "user_not_found", "User does not exist"));
        return new AuthDtos.CurrentUserResponse(user.getId(), user.getUsername(), user.getEmail());
    }

    private ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password");
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
