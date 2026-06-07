// AuthController — 注册、登录与当前用户（me）端点。
package com.speakcoach.controller;

import com.speakcoach.dto.AuthDtos;
import com.speakcoach.security.AuthPrincipal;
import com.speakcoach.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest req) {
        return ResponseEntity.ok(authService.login(req));
    }

    /**
     * 向指定邮箱发送 6 位数字验证码。{@code type} 决定流程上下文：
     * {@code "register"}（默认）对邮箱是否已存在不敏感，
     * {@code "login"} 则要求邮箱已绑定用户。60 秒重发冷却由验证码服务强制执行。
     */
    @PostMapping("/send-code")
    public ResponseEntity<Void> sendCode(@Valid @RequestBody AuthDtos.SendCodeRequest req) {
        authService.sendVerificationCode(req.email(), req.type());
        return ResponseEntity.ok().build();
    }

    /**
     * 校验 6 位数字验证码并返回短时效的 {@code verifyToken}（5 分钟 JWT，
     * subject=email，type=verify）。该 token 由 {@code /set-password} 消费
     * 以完成邮箱-OTP 注册流程。{@code /login-by-code} 不需要此 token。
     */
    @PostMapping("/verify-code")
    public ResponseEntity<AuthDtos.VerifyCodeResponse> verifyCode(@Valid @RequestBody AuthDtos.VerifyCodeRequest req) {
        String verifyToken = authService.verifyCode(req.email(), req.code());
        return ResponseEntity.ok(new AuthDtos.VerifyCodeResponse(verifyToken));
    }

    /**
     * 邮箱-OTP 注册流程的第二步（也是最后一步）。调用方必须已在最近 5 分钟内
     * 通过 {@code /verify-code} 获取 {@code verifyToken}。成功时返回与
     * {@code /register} / {@code /login} 相同的认证响应包络。
     */
    @PostMapping("/set-password")
    public ResponseEntity<AuthDtos.AuthResponse> setPassword(@Valid @RequestBody AuthDtos.SetPasswordRequest req) {
        return ResponseEntity.ok(authService.setPassword(req.verifyToken(), req.username(), req.password()));
    }

    /**
     * 邮箱 + 6 位数字验证码登录（无需密码）。调用方需先通过
     * {@code POST /api/auth/send-code}（{@code type="login"}）获取验证码。
     */
    @PostMapping("/login-by-code")
    public ResponseEntity<AuthDtos.AuthResponse> loginByCode(@Valid @RequestBody AuthDtos.VerifyCodeRequest req) {
        return ResponseEntity.ok(authService.loginByCode(req.email(), req.code()));
    }

    /**
     * 解析 JwtAuthFilter 挂载到 SecurityContext 的 principal。
     * 若无 principal 返回 401；若 JWT 指向的用户已不存在于数据库则返回 404。
     */
    @GetMapping("/me")
    public ResponseEntity<AuthDtos.CurrentUserResponse> me(
            @AuthenticationPrincipal AuthPrincipal principal) {
        Long userId = principal != null ? principal.userId() : null;
        return ResponseEntity.ok(authService.getCurrentUser(userId));
    }
}
