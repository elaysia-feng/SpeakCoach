// UserContext — 基于 Alibaba TransmittableThreadLocal（TTL）实现的、
// 每个请求的 user 与 request-id 存储。TTL 是 ThreadLocal 的直接替代品，
// 并且还具备：
//   1. 像 InheritableThreadLocal 那样把值继承到子线程；
//   2. 当任务通过 TtlRunnable.get(task) / TtlCallable.get(task) 包装，
//      或者在 JVM 启动时挂载 TTL javaagent
//      （-javaagent:transmittable-thread-local-agent.jar），
//      跨线程池任务切换时也能传递这些值。
// 过滤器负责写入值，Controller/Service 负责读取。务必在 finally 块中
// 调用 clear()（RequestIdFilter 和 JwtAuthFilter 都已经这样做了），
// 以避免在 Servlet 线程池上跨请求泄漏状态。
package com.speakcoach.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

/**
 * 当前请求的 ThreadLocal 上下文。
 *
 * <pre>
 *   // 在过滤器中：
 *   UserContext.setUserId(42L);
 *   UserContext.setRequestId(UUID.randomUUID().toString());
 *   try {
 *       chain.doFilter(req, resp);
 *   } finally {
 *       UserContext.clear();
 *   }
 *
 *   // 在 controller / service 中：
 *   Long userId = UserContext.getUserId();          // 未设置时为 null
 *   String reqId = UserContext.getRequestId();      // 未设置时为 null
 * </pre>
 */
public final class UserContext {

    private static final TransmittableThreadLocal<Long> USER_ID = new TransmittableThreadLocal<>();
    private static final TransmittableThreadLocal<String> REQUEST_ID = new TransmittableThreadLocal<>();

    private UserContext() {
        // 工具类
    }

    // ===== userId =====

    public static Long getUserId() {
        return USER_ID.get();
    }

    /**
     * 设置当前 user id。若传入 {@code null}，会抛出 {@link IllegalArgumentException}——
     * 若要移除该值，请改用 {@link #clear()}。
     */
    public static void setUserId(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null; call clear() to remove");
        }
        USER_ID.set(userId);
    }

    // ===== requestId =====

    public static String getRequestId() {
        return REQUEST_ID.get();
    }

    public static void setRequestId(String requestId) {
        if (requestId == null) {
            REQUEST_ID.remove();
        } else {
            REQUEST_ID.set(requestId);
        }
    }

    // ===== clear =====

    /**
     * 同时清空当前线程上的 userId 和 requestId。必须在任何调用
     * {@link #setUserId(Long)} 或 {@link #setRequestId(String)}
     * 的过滤器的 {@code finally} 块中调用——否则 Servlet 线程池会
     * 把这些值泄漏到下一个落在同一线程上的请求中。
     */
    public static void clear() {
        USER_ID.remove();
        REQUEST_ID.remove();
    }
}
