// AuthPrincipal — JWT 校验通过后，存入 SecurityContext 的 principal 对象。
package com.speakcoach.security;

import java.io.Serializable;

public record AuthPrincipal(Long userId, String username) implements Serializable {
}
