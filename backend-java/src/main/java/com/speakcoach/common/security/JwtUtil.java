// common.security.JwtUtil — common 模块对外暴露的通用 token 签发/校验器，
// 供未来其他服务（例如 Python 桥接、定时任务）使用。对外提供鉴权能力的是
// {@link com.speakcoach.security.JwtUtil}，后者接入到 JWT 鉴权过滤器
// 以及 Controller 层；两份实现都使用同一套 HS256 + jjwt 0.12.x 技术栈，
// 也能共用同一把密钥。
package com.speakcoach.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Token 辅助类。使用共享 HMAC 密钥签发和校验 HS256 JWT。
 * token 的 subject 是 userId；这里刻意不嵌入 username 声明，
 * 以保持 common 模块辅助类的最小化和可复用。
 */
@Component("commonJwtUtil")
@Slf4j
public class JwtUtil {

    private final SecretKey key;
    private final long expirationHours;

    public JwtUtil(
            @Value("${jwt.secret:}") String secret,
            @Value("${jwt.expiration-hours:168}") long expirationHours
    ) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("[common.security.JwtUtil] jwt.secret must be configured");
        }
        // jjwt 要求 HS256 密钥至少 256 位（32 字节）。
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalStateException("[common.security.JwtUtil] jwt.secret must be at least 32 bytes");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationHours = expirationHours;
    }

    @PostConstruct
    void logConfig() {
        log.info("[common.security.JwtUtil] initialized with expiration={}h", expirationHours);
    }

    /**
     * 签发一个 JWT，subject 是 {@code userId}（BIGINT 自增主键）的数值字符串形式。
     * 依照 JWS 规范，subject 声明始终是字符串；String 与 Long 之间的转换是无损的。
     */
    public String generateToken(Long userId) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(expirationHours * 3600L);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /**
     * 解析 token 并返回其 subject 中编码的 userId。当 token 缺失、为空、格式错误、
     * 签名不合法或已过期时返回 {@code null}。
     * 需要区分失败原因的调用方请使用 {@link #parse(String)}。
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
     * 解析并校验 token；仅当签名、格式和过期时间都合法且存在非空 subject 时返回 {@code true}。
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
     * 解析 token 并返回底层的 claims。遇到任何错误都会抛出——若仅需要布尔结果，
     * 请使用 {@link #validateToken(String)}。
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
