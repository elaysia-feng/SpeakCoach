// SpeakCoach 后端的 Spring Boot 启动入口。
package com.speakcoach;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.speakcoach.mapper")
public class SpeakCoachApplication {
    public static void main(String[] args) {
        SpringApplication.run(SpeakCoachApplication.class, args);
    }
}
