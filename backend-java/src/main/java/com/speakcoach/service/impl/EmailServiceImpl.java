// EmailServiceImpl — 通过 QQ SMTP（JavaMailSender）发送验证码邮件。
// 按设计契约使用 SimpleMailMessage（纯文本）。
// `from` 地址通过 @Value 从 `spring.mail.username` 解析。
package com.speakcoach.service.impl;

import com.speakcoach.exception.ApiException;
import com.speakcoach.service.EmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件服务实现
 * <ul>
 *   <li>使用 {@link JavaMailSender} 发送 QQ SMTP 邮件（plain text via {@link SimpleMailMessage}）</li>
 *   <li>主题：{@code 【SpeakCoach】验证码}</li>
 *   <li>正文：{@code 您的验证码是：<code>\n有效期5分钟，请勿泄露。}</li>
 *   <li>发送失败时记录 WARN 并抛出 {@link ApiException}（BAD_GATEWAY），
 *       由 controller / GlobalExceptionHandler 转换为标准 5xx 响应 — SMTP 故障
 *       是服务端问题，不应被表达为客户端请求错误</li>
 * </ul>
 */
@Service
@Slf4j
public class EmailServiceImpl implements EmailService {

    private static final String SUBJECT = "【SpeakCoach】验证码";
    private static final String BODY_TEMPLATE = "您的验证码是：%s%n有效期5分钟，请勿泄露。";

    private final JavaMailSender mailSender;
    private final String fromAddress;

    public EmailServiceImpl(
            JavaMailSender mailSender,
            @Value("${spring.mail.username}") String fromAddress
    ) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
    }

    @Override
    public void sendVerificationCode(String toEmail, String code) {
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "email_not_configured",
                    "邮件服务未配置，请设置 MAIL_USERNAME 和 MAIL_PASSWORD");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(toEmail);
        message.setSubject(SUBJECT);
        message.setText(String.format(BODY_TEMPLATE, code));
        try {
            mailSender.send(message);
            log.debug("[EmailService] sent verification code to {}", toEmail);
        } catch (MailException ex) {
            log.warn("[EmailService] failed to send verification code to {}: {}", toEmail, ex.getMessage());
            // SMTP 失败属于服务端问题，而非客户端请求问题 —— 应当以 502 Bad Gateway
            // 形式对外暴露，便于调用方区分"无法连接上游邮件服务器"与真正的
            // 4xx（例如请求负载格式错误）。
            throw new ApiException(HttpStatus.BAD_GATEWAY, "email_send_failed", "邮件发送失败，请稍后重试");
        }
    }
}
