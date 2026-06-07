// AuditService — 写入每个节点的追踪记录。
// 实现层绝不能让审计失败中断面向用户的主流程。
package com.speakcoach.service;

import com.speakcoach.dto.AuditEventDto;
import com.speakcoach.entity.AuditLog;

/**
 * 审计日志服务接口
 * <p>每个 Java 侧 / Python 侧的处理节点都应该写一条 {@code audit_log} 记录。</p>
 */
public interface AuditService {

    /**
     * 记录一次节点执行。
     *
     * @param userId        用户 ID
     * @param sessionId     会话 ID
     * @param turnId        回合 ID（可空）
     * @param nodeName      节点名称（例 {@code chat_controller}、{@code ai_grammar_node}）
     * @param inputJson     入参 JSON
     * @param outputJson    出参 JSON
     * @param latencyMs     节点耗时（毫秒）
     * @param modelName     调用的模型名（可空）
     * @param promptVersion 提示词版本（可空）
     */
    void record(Long userId,
                String sessionId,
                Integer turnId,
                String nodeName,
                String inputJson,
                String outputJson,
                Long latencyMs,
                String modelName,
                String promptVersion);

    /**
     * D3.8 —— 把 Python 侧审计写入器发来的事件落库。
     * 失败时返回 id 为 null 的瞬时 {@link AuditLog}（controller 仍会 202 受理）。
     */
    AuditLog ingestFromPython(AuditEventDto event);
}
