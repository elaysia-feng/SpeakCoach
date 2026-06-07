// ProductionSecretGuard —— Spring 启动后的"密钥体检"工具。
//
// 检测以下 4 个密钥是否仍是 dev 默认值 / 空值：
//   1. jwt.secret                      —— 必须 ≥32 字节(JwtUtil 已硬性校验)
//   2. app.internal.shared-secret      —— 内网端点鉴权(Python 调用 /api/internal/* 用)
//   3. spring.datasource.password      —— MySQL 密码
//   4. spring.mail.password            —— SMTP 密码
//
// 如果任一检测到为 dev 默认 / 空，打印醒目的 WARN banner 提醒运维覆盖。
// 默认在 dev 环境(通过 application.yml 的 `app.production-checks.enabled=true`
// 控制)开启；生产部署前可显式关掉以便消除噪音。

package com.speakcoach.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
@Order(0)  // 跑得早一点，让运维一启动就看到
@ConditionalOnProperty(name = "app.production-checks.enabled", havingValue = "true", matchIfMissing = true)
public class ProductionSecretGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProductionSecretGuard.class);

    /**
     * dev 默认占位值 —— 任何生产部署前必须用环境变量覆盖。
     * 这里集中维护，避免散落在 application.yml 各处。
     */
    private static final String DEV_INTERNAL_SECRET = "speakcoach-internal-dev-secret";

    private final String jwtSecret;
    private final String internalSecret;
    private final String dbPassword;
    private final String mailPassword;
    private final String env;  // 可选 dev / staging / production 标识

    public ProductionSecretGuard(
            @Value("${jwt.secret:}") String jwtSecret,
            @Value("${app.internal.shared-secret:}") String internalSecret,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${spring.mail.password:}") String mailPassword,
            @Value("${app.env:dev}") String env
    ) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret;
        this.internalSecret = internalSecret == null ? "" : internalSecret;
        this.dbPassword = dbPassword == null ? "" : dbPassword;
        this.mailPassword = mailPassword == null ? "" : mailPassword;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> findings = new ArrayList<>();

        // 1. JWT secret：JwtUtil 启动时会校验长度。这里只查空 / 极短。
        if (jwtSecret.isBlank()) {
            findings.add("jwt.secret is EMPTY — set JWT_SECRET env var (≥32 chars). "
                    + "JwtUtil will fail on first auth call otherwise.");
        } else if (jwtSecret.getBytes(StandardCharsets.UTF_8).length < 32) {
            findings.add("jwt.secret is TOO SHORT (<32 bytes) — set JWT_SECRET to a longer value.");
        }

        // 2. Internal shared secret —— 攻击面最大的一个(Python 调 /api/internal/* 鉴权用)
        if (internalSecret.isBlank()) {
            findings.add("app.internal.shared-secret is EMPTY — set INTERNAL_SHARED_SECRET env var. "
                    + "Internal endpoints (/api/internal/**) are currently UNPROTECTED.");
        } else if (DEV_INTERNAL_SECRET.equals(internalSecret)) {
            findings.add("app.internal.shared-secret is still DEV DEFAULT '"
                    + DEV_INTERNAL_SECRET + "' — set INTERNAL_SHARED_SECRET env var. "
                    + "Internal endpoints are currently ACCEPTING the dev token.");
        }

        // 3. DB password
        if (dbPassword.isBlank()) {
            findings.add("spring.datasource.password is EMPTY — set MYSQL_PASSWORD env var. "
                    + "DB connection will fail on first query otherwise.");
        }

        // 4. Mail password
        if (mailPassword.isBlank()) {
            findings.add("spring.mail.password is EMPTY — set MAIL_PASSWORD env var. "
                    + "Email sending (verification codes) will fail when triggered.");
        }

        if (findings.isEmpty()) {
            log.info("[prod-check] env={} — all 4 secrets are configured.", env);
            return;
        }

        // 用 banner 样式打印 —— 让运维一眼看到
        log.warn("╔══════════════════════════════════════════════════════════════════╗");
        log.warn("║  PRODUCTION SECRET CHECK — env={}                              ", env);
        log.warn("║  Found {} dev-default / empty secret(s) — MUST override before   ", findings.size());
        log.warn("║  production deployment:                                          ");
        log.warn("╠══════════════════════════════════════════════════════════════════╣");
        for (int i = 0; i < findings.size(); i++) {
            log.warn("║  {}. {}", i + 1, findings.get(i));
        }
        log.warn("╠══════════════════════════════════════════════════════════════════╣");
        log.warn("║  To silence this warning in dev:                                  ");
        log.warn("║    app.production-checks.enabled=false                            ");
        log.warn("╚══════════════════════════════════════════════════════════════════╝");
    }
}
