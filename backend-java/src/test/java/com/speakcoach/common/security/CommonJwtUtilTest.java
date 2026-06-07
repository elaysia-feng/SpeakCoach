package com.speakcoach.common.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommonJwtUtilTest {

    @Test
    void rejectsBlankSecret() {
        assertThatThrownBy(() -> new JwtUtil("", 168L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret must be configured");
    }

    @Test
    void rejectsShortSecret() {
        assertThatThrownBy(() -> new JwtUtil("short-secret", 168L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void acceptsStrongSecret() {
        assertThatCode(() -> new JwtUtil("0123456789abcdef0123456789abcdef", 168L))
                .doesNotThrowAnyException();
    }
}
