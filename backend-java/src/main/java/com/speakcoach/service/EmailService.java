// EmailService — 向用户发送验证码邮件。
// 具体实现位于 com.speakcoach.service.impl.EmailServiceImpl。
package com.speakcoach.service;

/**
 * 邮件服务接口
 * <p>封装向用户邮箱发送验证码邮件的抽象。具体实现位于
 * {@code com.speakcoach.service.impl.EmailServiceImpl}。</p>
 */
public interface EmailService {

    /**
     * 发送验证码邮件。
     *
     * @param toEmail 收件人邮箱
     * @param code    6 位数字验证码（明文，未脱敏）
     */
    void sendVerificationCode(String toEmail, String code);
}
