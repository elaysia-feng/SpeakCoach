package com.speakcoach.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "oss")
public class OssProperties {

    private String accessKeyId;
    private String accessKeySecret;
    private String endpoint = "oss-cn-beijing.aliyuncs.com";
    private String bucketName = "speak-coach";
    private String domain = "https://speak-coach.oss-cn-beijing.aliyuncs.com";
}
