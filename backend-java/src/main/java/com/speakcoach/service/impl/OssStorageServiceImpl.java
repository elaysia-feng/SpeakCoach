package com.speakcoach.service.impl;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import com.speakcoach.config.OssProperties;
import com.speakcoach.exception.ApiException;
import com.speakcoach.service.OssStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
@Slf4j
public class OssStorageServiceImpl implements OssStorageService {

    private final OssProperties properties;

    @Override
    public String uploadAudio(Path file, String objectKey, String contentType) {
        if (isBlank(properties.getAccessKeyId()) || isBlank(properties.getAccessKeySecret())) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "oss_not_configured",
                    "OSS credentials are not configured");
        }
        String endpoint = normalizeEndpoint(properties.getEndpoint());
        OSS client = new OSSClientBuilder().build(
                endpoint,
                properties.getAccessKeyId(),
                properties.getAccessKeySecret()
        );
        try (InputStream in = Files.newInputStream(file)) {
            ObjectMetadata metadata = new ObjectMetadata();
            metadata.setContentType(isBlank(contentType) ? "application/octet-stream" : contentType);
            metadata.setContentLength(Files.size(file));
            PutObjectRequest request = new PutObjectRequest(properties.getBucketName(), objectKey, in, metadata);
            client.putObject(request);
            return normalizeDomain(properties.getDomain()) + "/" + objectKey;
        } catch (IOException ex) {
            log.warn("Failed to read audio before OSS upload: {}", ex.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "audio_store_failed", "Failed to read audio");
        } catch (Exception ex) {
            log.warn("Failed to upload audio to OSS key={}: {}", objectKey, ex.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "oss_upload_failed", "Failed to upload audio");
        } finally {
            client.shutdown();
        }
    }

    private static String normalizeEndpoint(String endpoint) {
        if (isBlank(endpoint)) {
            return "https://oss-cn-beijing.aliyuncs.com";
        }
        String trimmed = endpoint.trim();
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed;
        }
        return "https://" + trimmed;
    }

    private static String normalizeDomain(String domain) {
        if (isBlank(domain)) {
            return "";
        }
        String trimmed = domain.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
