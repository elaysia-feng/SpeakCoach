// 所有 Controller 方法（以及全局异常处理器）共用的统一 API 响应封装。
// code: 200 表示成功，4xx 表示客户端错误，5xx 表示服务端错误。出错时 `data` 为 null。
// `requestId` 在存在时从 UserContext.REQUEST_ID 自动填充。
package com.speakcoach.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.speakcoach.common.context.UserContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

/**
 * 统一的 API 响应封装。
 *
 * <pre>
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": { ... },
 *   "requestId": "uuid",
 *   "timestamp": 1718000000000
 * }
 * </pre>
 *
 * @param <T> 负载类型（对于无 body 的成功响应可以为 {@code Void}）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 200 = 成功，非 200 = 错误。 */
    private int code;

    /** 人类可读的提示信息（中英文均可 —— UI 端可以直接原样展示）。 */
    private String message;

    /** 业务负载，出错时为 {@code null}。 */
    private T data;

    /** 当存在时，从 {@link UserContext#REQUEST_ID} 回显。 */
    private String requestId;

    /** 构建此封装时的 epoch 毫秒数。 */
    private long timestamp;

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private static String currentRequestId() {
        try {
            return UserContext.getRequestId();
        } catch (Exception ignored) {
            return null;
        }
    }

    // ===== 成功响应工厂方法 =====

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .code(200)
                .message("success")
                .data(data)
                .requestId(currentRequestId())
                .timestamp(now())
                .build();
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    // ===== 错误响应工厂方法 =====

    public static <T> ApiResponse<T> error(int code, String message) {
        return ApiResponse.<T>builder()
                .code(code)
                .message(message)
                .data(null)
                .requestId(currentRequestId())
                .timestamp(now())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return error(400, message);
    }
}
