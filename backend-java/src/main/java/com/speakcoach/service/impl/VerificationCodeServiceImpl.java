// VerificationCodeServiceImpl — 基于 Redis 的 6 位验证码存储。
// Key：
//   verification:<email>           → 6 位数字验证码，TTL = email.code.ttl-seconds（默认 300）
//   verification:cooldown:<email>  → "1"，TTL = email.code.cooldown-seconds（默认 60）
package com.speakcoach.service.impl;

import com.speakcoach.service.VerificationCodeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 邮箱验证码服务实现
 * <ul>
 *   <li>{@code issueCode}：生成 [000000, 999999] 的 6 位数字码，写入 Redis 并刷新冷却 key</li>
 *   <li>{@code verifyAndConsume}：取出后立即删除（一次性消费）</li>
 *   <li>{@code isInCooldown}：通过 {@code hasKey} 检查冷却 key 是否存在</li>
 * </ul>
 */
@Service
@Slf4j
public class VerificationCodeServiceImpl implements VerificationCodeService {

    private static final String CODE_KEY_PREFIX = "verification:";
    private static final String COOLDOWN_KEY_PREFIX = "verification:cooldown:";

    private final StringRedisTemplate redisTemplate;
    private final long ttlSeconds;
    private final long cooldownSeconds;

    public VerificationCodeServiceImpl(
            StringRedisTemplate redisTemplate,
            @Value("${email.code.ttl-seconds:300}") long ttlSeconds,
            @Value("${email.code.cooldown-seconds:60}") long cooldownSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.ttlSeconds = ttlSeconds;
        this.cooldownSeconds = cooldownSeconds;
    }

    @Override
    public String issueCode(String email) {
        int n = ThreadLocalRandom.current().nextInt(0, 1_000_000);
        String code = String.format("%06d", n);
        redisTemplate.opsForValue().set(CODE_KEY_PREFIX + email, code, ttlSeconds, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(COOLDOWN_KEY_PREFIX + email, "1", cooldownSeconds, TimeUnit.SECONDS);
        log.debug("[VerificationCodeService] issued code for {} (ttl={}s, cooldown={}s)",
                email, ttlSeconds, cooldownSeconds);
        return code;
    }

    @Override
    public boolean verifyAndConsume(String email, String code) {
        String key = CODE_KEY_PREFIX + email;
        String stored = redisTemplate.opsForValue().get(key);
        if (stored == null) {
            return false;
        }
        if (!stored.equals(code)) {
            return false;
        }
        redisTemplate.delete(key);
        return true;
    }

    @Override
    public boolean isInCooldown(String email) {
        Boolean exists = redisTemplate.hasKey(COOLDOWN_KEY_PREFIX + email);
        return Boolean.TRUE.equals(exists);
    }
}
