// VerificationCodeService — 6 位邮箱验证码的签发、校验消费与冷却期检查。
// 验证码存放在 Redis（StringRedisTemplate）中。
package com.speakcoach.service;

/**
 * 邮箱验证码服务接口
 * <p>封装验证码的生成、校验与重发冷却逻辑。底层使用 Redis 存储，
 * 具体实现位于 {@code com.speakcoach.service.impl.VerificationCodeServiceImpl}。</p>
 */
public interface VerificationCodeService {

    /**
     * 生成 6 位数字验证码，写入 Redis（5 分钟 TTL），并刷新 60 秒重发冷却。
     *
     * @param email 收件人邮箱
     * @return 生成的明文验证码
     */
    String issueCode(String email);

    /**
     * 校验并消费验证码：与 Redis 中的值一致则删除并返回 true，不一致或已过期返回 false。
     *
     * @param email 收件人邮箱
     * @param code  用户提交的验证码
     * @return 是否通过校验
     */
    boolean verifyAndConsume(String email, String code);

    /**
     * 是否处于重发冷却期（60 秒内已发过）。
     *
     * @param email 收件人邮箱
     * @return true 表示处于冷却期
     */
    boolean isInCooldown(String email);
}
