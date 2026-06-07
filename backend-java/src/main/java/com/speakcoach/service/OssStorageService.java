package com.speakcoach.service;

import java.nio.file.Path;

public interface OssStorageService {

    String uploadAudio(Path file, String objectKey, String contentType);
}
