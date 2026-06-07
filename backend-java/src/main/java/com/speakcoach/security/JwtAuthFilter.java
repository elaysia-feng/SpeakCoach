// 无状态 JWT 鉴权过滤器 —— 解析 Bearer token，校验通过则设置 SecurityContext。
// 对于合法 token，过滤器还会再从数据库重新加载用户，这样针对一个已被删除（或改过名字）的用户
// 签发的 JWT 就无法继续维持会话。若用户记录不存在，则把该请求视为匿名。
package com.speakcoach.security;

import com.speakcoach.common.context.UserContext;
import com.speakcoach.entity.User;
import com.speakcoach.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        try {
            if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                try {
                    Claims claims = jwtUtil.parse(token);
                    // subject 是自增 BIGINT user id 的数值字符串形式。
                    String userIdStr = claims.getSubject();
                    if (StringUtils.hasText(userIdStr)) {
                        Long userId = Long.valueOf(userIdStr);
                        // #MM —— 重新从数据库加载用户，杜绝使用已删除（或已改名字）账号的过期 token。
                        // 如果记录已不存在，则按匿名处理 -> 受保护接口将返回 401。
                        // 同时优先使用数据库中的 username 而非 token 中的 claim，
                        // 保证下一次请求就能反映出账号更名。
                        User userRow = userMapper.selectById(userId);
                        if (userRow == null) {
                            log.warn("JWT references missing user id={} — treating as anonymous", userId);
                        } else {
                            String username = userRow.getUsername();
                            AuthPrincipal principal = new AuthPrincipal(userId, username);
                            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                    principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(auth);
                            // #49 —— 将解析出的 userId 传递到请求级 ThreadLocal，
                            // 以便应用代码（含 controller advice）可以通过
                            // UserContext.getUserId() 读取，不必再次解析 principal。
                            UserContext.setUserId(userId);
                        }
                    }
                } catch (JwtException ex) {
                    // #30 —— 将日志级别由 DEBUG 提升为 WARN，
                    // 这样 JWT 失败在生产环境的日志中也能被看到（prod 默认会屏蔽 DEBUG）。
                    log.warn("JWT parse failed: {}", ex.getMessage());
                    // 保持 SecurityContext 为空 -> 受保护接口返回 401。
                } catch (NumberFormatException ex) {
                    // 旧版本（UUID 字符串 subject）签发的 token —— 视为非法 token。
                    log.warn("JWT subject is not a numeric user id: {}", ex.getMessage());
                }
            }
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
            UserContext.clear();
        }
    }
}
