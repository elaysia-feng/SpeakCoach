// SecretVerifier —— 共享密钥校验工具。
// 单例风格，无 Spring 依赖。共享密钥在 application.yml 中通过
// {@code app.internal.shared-secret} 注入；环境变量 {@code INTERNAL_SHARED_SECRET} 可覆盖。
package com.speakcoach.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretVerifier {

    private static final String DEFAULT_DEV_SECRET = "speakcoach-internal-dev-secret";

    private static volatile String currentSecret = DEFAULT_DEV_SECRET;

    public SecretVerifier(@Value("${app.internal.shared-secret:" + DEFAULT_DEV_SECRET + "}") String secret) {
        if (secret != null && !secret.isBlank()) {
            currentSecret = secret;
        }
    }

    public static boolean matches(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        return constantTimeEquals(candidate, currentSecret);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
