// JWT 辅助类 —— HS256 签发/校验，24 小时过期，claims：sub=userId, username。
// 注意：com.speakcoach.common.security.JwtUtil 还有一个同级实现，
// 用于 generic/common 场景。两份实现都基于 jjwt 0.12.x，并可共用同一把 HMAC 密钥；
// 接入到 JwtAuthFilter 的是本类，common 模块中的那个是面向未来服务的可复用辅助类。
package com.speakcoach.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration-ms}") long expirationMs
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("jwt.secret must be configured");
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("jwt.secret must be at least 32 bytes");
        }
        // HS256 要求密钥至少 256 位（32 字节）。
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    /**
     * 为指定用户签发 JWT。username 单独作为一个 claim 存放，
     * 下游代码无需访问数据库即可解析出 username。
     *
     * <p>userId 是 {@code Long}（BIGINT 自增主键），但会以数值字符串形式写入 JWT subject——
     * JWS 规范仅支持字符串型 subject，而 {@link Long#toString()} 与
     * {@link Long#valueOf(String)} 之间的转换是无损的。</p>
     */
    public String generateToken(Long userId, String username) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * 便捷重载：给只持有 userId 的调用方使用（例如 token 刷新流程）。
     * username 默认为空字符串；{@code username} claim 仍会存在
     * （Spring Security 不会对空 claim 做特殊处理）。
     */
    public String generateToken(Long userId) {
        return generateToken(userId, "");
    }

    /**
     * 解析签名后的 JWT 并返回其 claims。当签名无效、token 格式错误或已过期时
     * 抛出 {@link JwtException} —— 调用方应将 token 视为未认证。
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 从 JWT 中提取 userId（subject）。任何失败都返回 {@code null}——
     * 若要区分「token 非法」与「subject 缺失」，请使用 {@link #parse}。
     */
    public String parseUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = parse(token);
            String sub = claims.getSubject();
            return (sub != null && !sub.isBlank()) ? sub : null;
        } catch (JwtException ex) {
            return null;
        }
    }

    /**
     * 仅当 token 格式良好、HS256 签名能通过配置的密钥校验且未过期时返回 {@code true}。
     * 过期的 token 返回 {@code false}（不会抛出异常）。
     */
    public boolean validateToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Claims claims = parse(token);
            return claims.getSubject() != null && !claims.getSubject().isBlank();
        } catch (ExpiredJwtException ex) {
            return false;
        } catch (JwtException ex) {
            return false;
        }
    }

    /**
     * 由邮箱验证码注册流程使用的 5 分钟 verify-token：subject=email，
     * claim 为 {@code type=verify}。签名密钥与主 JWT 的 HMAC 密钥相同。
     */
    public String generateVerifyToken(String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .claim("type", "verify")
                .issuedAt(new Date(now))
                .expiration(new Date(now + 5 * 60 * 1000L))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 verify-token 并返回 email（subject）。当 token 缺失、已过期、
     * 签名不合法或未携带 {@code type=verify} claim 时抛出
     * {@link IllegalArgumentException}——调用方应将以上情况一律视为 400 非法 token。
     */
    public String getEmailFromVerifyToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("verify token is empty");
        }
        Claims claims;
        try {
            claims = parse(token);
        } catch (JwtException ex) {
            throw new IllegalArgumentException("invalid verify token: " + ex.getMessage());
        }
        Object type = claims.get("type");
        if (!"verify".equals(type)) {
            throw new IllegalArgumentException("not a verify token");
        }
        String email = claims.getSubject();
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("verify token has no subject");
        }
        return email;
    }
}
