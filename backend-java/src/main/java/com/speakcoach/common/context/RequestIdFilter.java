// RequestIdFilter — 为每一个进入的 HTTP 请求分配一个 request-id，
// 并通过以下三种方式暴露给请求处理流水线的其余部分：
// (a) X-Request-Id 响应头；
// (b) SLF4J 的 MDC，键为 "requestId"，用于日志关联；
// (c) UserContext，便于在错误响应封装中携带它的应用代码读取。
package com.speakcoach.common.context;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Servlet 过滤器，确保每个请求都拥有用于日志关联的稳定 id。
 * 当入站请求中带有 {@code X-Request-Id} 头时（调用方自带 trace id）予以保留，
 * 否则回退到新生成的 UUID v4。
 * 在 {@link com.speakcoach.security.JwtAuthFilter} 之前执行，
 * 因此 userId 解析在需要时也能复用同一个 MDC 槽位。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // #48 — 客户端传入的 X-Request-Id 需要被清洗：仅允许字母数字和短横线，
    // 长度 1..64。其它任何内容都会被替换成服务端生成的 UUID。
    // 这样可以避免日志注入（CR/LF、ANSI 转义、控制字符）污染那些会把该 id
    // 插值进日志的日志行。
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("^[A-Za-z0-9\\-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = request.getHeader(HEADER);
        if (requestId == null || requestId.isBlank() || !SAFE_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, requestId);
            UserContext.setRequestId(requestId);
            response.setHeader(HEADER, requestId);
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
            // clear() 会同时清除 userId 和 requestId；JwtAuthFilter 也会调用 clear()，
            // 再次置空是无害的。
            UserContext.clear();
        }
    }
}
