// AudioController — 提供由 TtsServiceImpl 写入的 WAV 文件访问服务，
// 文件路径位于 storage/audio/{userId}/{sessionId}/turn_{turnId}.wav。
//
// 行为：
//  - 路径要求 JWT 认证，归属权通过 SecurityContext 中的 AuthPrincipal 强制校验。
//  - 文件不存在时返回 404。
//  - 路径中的 userId 与调用方不匹配时返回 403。
//  - 支持 HTTP Range 头（单段字节范围）以实现音频 seek；返回 206 并携带
//    Content-Range 与请求切片。多段及仅后缀的 Range 请求回退为完整文件。
//
package com.speakcoach.controller;

import com.speakcoach.config.TtsProperties;
import com.speakcoach.exception.ApiException;
import com.speakcoach.security.AuthPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 音频文件控制器
 * <p>提供 GET {@code /api/audio/{userId}/{sessionId}/{filename}}，从
 * {@link TtsProperties#getAudioStoragePath()} 解析并下发 WAV 文件，支持 HTTP Range 寻址。</p>
 */
@RestController
@RequestMapping("/api/audio")
@RequiredArgsConstructor
@Slf4j
public class AudioController {

    private final TtsProperties tts;

    @GetMapping("/{userId}/{sessionId}/{filename:.+}")
    public ResponseEntity<Resource> serve(@PathVariable String userId,
                                          @PathVariable String sessionId,
                                          @PathVariable String filename,
                                          @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {

        // 1. 授权（AuthZ）：principal.userId()（Long）必须与路径中的 userId 段（String）匹配。
        //    将路径段解析为 Long，使比较类型安全，并通过 400 捕获格式错误的 URL，
        //    而非让其继续访问磁盘。
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof AuthPrincipal principal)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "audio_unauthenticated",
                    "Authentication required to access audio");
        }
        Long pathUserId;
        try {
            pathUserId = Long.parseLong(userId);
        } catch (NumberFormatException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "audio_bad_userid",
                    "Invalid userId path segment");
        }
        if (principal.userId() != pathUserId.longValue()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "audio_forbidden",
                    "Audio does not belong to current user");
        }

        // 2. 校验文件名 —— 只允许受控音频后缀且不能包含路径分隔符。
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")
                || !isSupportedAudio(filename)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "audio_bad_filename",
                    "Invalid audio filename");
        }

        // 3. 在配置的存储根目录下解析磁盘路径，并拒绝任何逃逸出该根目录的请求
        //    （纵深防御，尽管上文已对输入做了清理）。
        Path storageRoot = Paths.get(tts.getAudioStoragePath() != null ? tts.getAudioStoragePath() : "./storage/audio")
                .toAbsolutePath()
                .normalize();
        Path target = storageRoot.resolve(String.valueOf(pathUserId)).resolve(sessionId).resolve(filename).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "audio_bad_path",
                    "Audio path escapes storage root");
        }

        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            log.debug("Audio not found: {}", target);
            throw new ApiException(HttpStatus.NOT_FOUND, "audio_not_found",
                    "Audio file not found");
        }

        long fileLength;
        try {
            fileLength = Files.size(target);
        } catch (IOException io) {
            log.error("Failed to stat audio file {}: {}", target, io.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "audio_io_error",
                    "Failed to read audio file");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaTypeFor(filename));
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setCacheControl("private, max-age=300");

        // 4. 无 Range 头 → 返回 200 与完整文件。
        if (rangeHeader == null || rangeHeader.isBlank()) {
            headers.setContentLength(fileLength);
            return new ResponseEntity<>(new FileSystemResource(target), headers, HttpStatus.OK);
        }

        // 5. 支持单段字节范围请求；解析失败或多段请求（multipart/byteranges 暂未实现）
        //    时回退为完整文件。
        try {
            List<HttpRange> ranges = HttpRange.parseRanges(rangeHeader);
            if (ranges.isEmpty() || ranges.size() > 1) {
                headers.setContentLength(fileLength);
                return new ResponseEntity<>(new FileSystemResource(target), headers, HttpStatus.OK);
            }
            HttpRange range = ranges.get(0);
            long start = range.getRangeStart(fileLength);
            long end = range.getRangeEnd(fileLength);
            if (start > end || start >= fileLength) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength);
                return new ResponseEntity<>(headers, HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
            }
            long sliceLen = end - start + 1;
            InputStream in = Files.newInputStream(target);
            try {
                skipFully(in, start);
            } catch (IOException ex) {
                in.close();
                throw ex;
            }

            headers.setContentLength(sliceLen);
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + fileLength);
            return new ResponseEntity<>(new InputStreamResource(new BoundedInputStream(in, sliceLen)),
                    headers, HttpStatus.PARTIAL_CONTENT);
        } catch (IllegalArgumentException badRange) {
            log.debug("Invalid Range header '{}', serving full file", rangeHeader);
            headers.setContentLength(fileLength);
            return new ResponseEntity<>(new FileSystemResource(target), headers, HttpStatus.OK);
        } catch (IOException io) {
            log.error("Range read failed for {}: {}", target, io.getMessage());
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "audio_io_error",
                    "Failed to read audio range");
        }
    }

    /** 将输入流移动到 Range 起点，避免把整段 Range 读入堆内存。 */
    private static void skipFully(InputStream in, long offset) throws IOException {
        long skipped = 0;
        while (skipped < offset) {
            long step = in.skip(offset - skipped);
            if (step <= 0) {
                if (in.read() == -1) {
                    throw new IOException("EOF before range offset " + offset);
                }
                step = 1;
            }
            skipped += step;
        }
    }

    private static boolean isSupportedAudio(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".wav")
                || lower.endsWith(".webm")
                || lower.endsWith(".mp3")
                || lower.endsWith(".m4a")
                || lower.endsWith(".ogg");
    }

    private static MediaType mediaTypeFor(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".webm")) {
            return MediaType.parseMediaType("audio/webm");
        }
        if (lower.endsWith(".mp3")) {
            return MediaType.parseMediaType("audio/mpeg");
        }
        if (lower.endsWith(".m4a")) {
            return MediaType.parseMediaType("audio/mp4");
        }
        if (lower.endsWith(".ogg")) {
            return MediaType.parseMediaType("audio/ogg");
        }
        return MediaType.parseMediaType("audio/wav");
    }

    private static final class BoundedInputStream extends FilterInputStream {
        private long remaining;

        private BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = super.read();
            if (value != -1) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) {
                remaining -= n;
            }
            return n;
        }
    }
}
