// AuditEventDto —— Python 侧逐节点审计的入参 DTO。
// POST /api/internal/audit/ingest 的请求体。所有 JSON 字段
// 均为 Python 端预序列化的字符串，避免在 Java 侧再次解析。
package com.speakcoach.dto;

public record AuditEventDto(
        Long userId,
        String sessionId,
        Long turnId,
        String nodeName,
        String inputJson,
        String outputJson,
        Long latencyMs,
        String modelName,
        String promptVersion
) {
}
