// ApiException —— 统一的错误类型，携带 HTTP 状态码 + 机器可读的错误编码。
package com.speakcoach.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String error;

    public ApiException(HttpStatus status, String error, String message) {
        super(message);
        this.status = status;
        this.error = error;
    }
}
