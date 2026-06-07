// AuditServiceImpl — 为 Java 侧的每个流水线阶段写入一条 audit_log 记录。
// 关键：审计写入失败绝不能中断面向用户的主流程。
// 保存操作被 try/catch 包裹；任何异常都会被记录为警告并吞掉，方法正常返回。
// 这与 service/AuditService.java 中的接口契约及 .omc/plans/speakcoach-mvp.md 的要求保持一致。
package com.speakcoach.service.impl;

import com.speakcoach.dto.AuditEventDto;
import com.speakcoach.entity.AuditLog;
import com.speakcoach.mapper.AuditLogMapper;
import com.speakcoach.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 审计日志服务实现
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditServiceImpl implements AuditService {

    private final AuditLogMapper auditLogMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId,
                       String sessionId,
                       Integer turnId,
                       String nodeName,
                       String inputJson,
                       String outputJson,
                       Long latencyMs,
                       String modelName,
                       String promptVersion) {
        try {
            // #29 —— 实体将 created_at 标注为 insertable=false/updatable=false，
            // 以保证 DB DEFAULT CURRENT_TIMESTAMP 为唯一真值来源。INSERT 时绝不能
            // 传递 createdAt（否则 MyBatis-Plus 会为该只读列写入 NULL，触发
            // NOT NULL / 默认值相关异常）。
            AuditLog row = AuditLog.builder()
                    .userId(userId)
                    .sessionId(sessionId)
                    .turnId(turnId)
                    .nodeName(nodeName)
                    .inputJson(inputJson)
                    .outputJson(outputJson)
                    .latencyMs(latencyMs)
                    .modelName(modelName)
                    .promptVersion(promptVersion)
                    .build();
            auditLogMapper.insert(row);
        } catch (Exception ex) {
            // 绝不让审计失败破坏面向用户的主流程。
            log.warn("audit_log insert failed (node={}): {}", nodeName, ex.getMessage());
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog ingestFromPython(AuditEventDto event) {
        // 兜底：任何情况下都返回一个 AuditLog（id 可能为 null），让 controller 始终 202 受理。
        try {
            if (event == null) {
                log.warn("audit ingestFromPython received null event");
                return AuditLog.builder().id(null).build();
            }
            AuditLog row = AuditLog.builder()
                    .userId(event.userId())
                    .sessionId(event.sessionId())
                    .turnId(event.turnId() == null ? null : event.turnId().intValue())
                    .nodeName(event.nodeName())
                    .inputJson(event.inputJson())
                    .outputJson(event.outputJson())
                    .latencyMs(event.latencyMs())
                    .modelName(event.modelName())
                    .promptVersion(event.promptVersion())
                    .createdAt(LocalDateTime.now())
                    .build();
            auditLogMapper.insert(row);
            return row;
        } catch (Exception ex) {
            // 旁路处理：审计失败绝不能阻断 Python 侧主流程。
            log.warn("audit_log insert from python failed (node={}): {}",
                    event == null ? null : event.nodeName(), ex.getMessage());
            return AuditLog.builder().id(null).build();
        }
    }
}
