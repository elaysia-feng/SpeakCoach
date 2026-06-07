// BusinessException — 领域层失败类型，带一个映射到 ApiResponse.code 的数字 code。
// 当错误是「预期内的」业务状态时（例如会话未找到、用户已存在、由校验引发的冲突），
// 用本类代替 HttpStatus 编码。
package com.speakcoach.common.exception;

import lombok.Getter;

/**
 * 业务层失败的基类。带有一个数字 code，会被全局异常处理器回填到
 * {@link com.speakcoach.common.ApiResponse#code}。
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public BusinessException(int code) {
        super("business_error");
        this.code = code;
    }

    // ===== 常用工厂方法 =====

    public static BusinessException notFound(String message) {
        return new BusinessException(404, message);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(403, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, message);
    }

    public static BusinessException serverError(String message) {
        return new BusinessException(500, message);
    }
}
