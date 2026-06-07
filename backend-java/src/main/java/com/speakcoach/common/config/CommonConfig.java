// CommonConfig — 公共的横切 Bean：一个识别 java.time 类型的 ObjectMapper，
// 以及一个允许测试在不打散 System.currentTimeMillis() 调用的情况下伪造「当前时间」的 Clock。
package com.speakcoach.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 每个服务都共享的公共 Bean。{@link ObjectMapper} Bean 与 Spring Boot 自动装配出来的一致，
 * 这里显式声明只是为了：
 * <ul>
 *   <li>注册 {@link JavaTimeModule}（在 DTO 中支持 LocalDateTime 序列化）。</li>
 *   <li>将日期输出为 ISO-8601 字符串而非数值时间戳——这正是 Vite/React 前端所期望的。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties
public class CommonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    /**
     * 系统时钟。测试时可以用 {@code Clock.fixed(...)} 替换该 Bean，
     * 从而使时间敏感的逻辑变得确定。
     */
    @Bean
    public Clock systemClock() {
        return Clock.systemDefaultZone();
    }
}
