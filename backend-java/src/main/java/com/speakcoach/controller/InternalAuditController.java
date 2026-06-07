// InternalAuditController — D3.8 逐节点审计落库端点。
// 供 Python 侧审计写入器 (backend-python/app/audit.py) 调用，
// 把每个节点的执行事件持久化到 audit_log 表。鉴权方式与
// InternalProfileController 一致：通过 X-Internal-Token header
// 校验；与 application.yml 中的 app.internal.token 环境变量配对。
// 所有调用都来自 backend-python（同一内网，不向公网暴露）。
package com.speakcoach.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.speakcoach.dto.AuditEventDto;
import com.speakcoach.entity.AuditLog;
import com.speakcoach.exception.ApiException;
import com.speakcoach.mapper.AuditLogMapper;
import com.speakcoach.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/internal/audit")
@RequiredArgsConstructor
@Slf4j
public class InternalAuditController {

    private final AuditService auditService;
    private final AuditLogMapper auditLogMapper;

    /**
     * 与 {@code INTERNAL_API_TOKEN} 环境变量配对的内网 token。空字符串表示
     * "不校验"（开发模式）；非空时必须与 {@code X-Internal-Token} header 完全相等。
     */
    @Value("${app.internal.token:}")
    private String internalToken;

    /**
     * POST /api/internal/audit/ingest —— Python 端审计写入器逐节点回调。
     * 失败按旁路处理（service impl 内部 try/catch），返回 202 告知调用方
     * 已经受理；id 可能为 null（落库失败时也返回 202，避免触发重试）。
     */
    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestBody AuditEventDto event) {
        requireInternalToken(token);
        if (event == null || event.nodeName() == null || event.nodeName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request",
                    "nodeName is required");
        }
        AuditLog row = auditService.ingestFromPython(event);
        Map<String, Object> body = new HashMap<>();
        body.put("accepted", true);
        body.put("auditId", row == null ? null : row.getId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /**
     * GET /api/internal/audit/recent?limit=20 —— 测试辅助端点。
     * 按 id DESC 返回最近 N 条 audit_log 记录，便于人工核对。
     */
    @GetMapping("/recent")
    public ResponseEntity<List<AuditLog>> recent(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestParam(value = "limit", required = false, defaultValue = "20") int limit) {
        requireInternalToken(token);
        int safeLimit = Math.max(1, Math.min(200, limit));
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(AuditLog::getId).last("LIMIT " + safeLimit);
        return ResponseEntity.ok(auditLogMapper.selectList(wrapper));
    }

    private void requireInternalToken(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            // 未配置 token —— 开发模式放行（与现有 InternalProfileController 行为一致）。
            return;
        }
        if (token == null || !internalToken.equals(token)) {
            log.warn("Internal audit endpoint rejected missing/invalid X-Internal-Token");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "internal_token_invalid",
                    "X-Internal-Token header is missing or invalid");
        }
    }
}
