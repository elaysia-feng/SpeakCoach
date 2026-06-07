// SessionService — 口语会话生命周期契约。
// 实现层强制执行归属权：调用方必须传入已认证的 userId。
package com.speakcoach.service;

import com.speakcoach.dto.SessionDtos;
import com.speakcoach.entity.SpeakingSession;

import java.util.List;

/**
 * 口语会话服务接口
 * <p>负责创建会话、按 ID 获取会话（带归属校验）、列出当前用户全部会话、结束会话并写入
 * 摘要/能力画像，以及把实体转成对外响应 DTO。</p>
 */
public interface SessionService {

    /**
     * 为指定用户创建一条新的口语会话，状态为 {@code active}，turn 计数为 0。
     *
     * @param userId 当前登录用户的 ID
     * @param scene  场景标识（free_talk / interview / travel 等）
     * @return 已写入数据库的会话实体（已包含 created_at / updated_at）
     */
    SpeakingSession create(Long userId, String scene);

    /**
     * 按 ID 查询会话，并校验归属权。
     *
     * @param sessionId 会话 ID
     * @param userId    当前登录用户的 ID
     * @return 会话实体
     * @throws com.speakcoach.exception.ApiException 404 {@code session_not_found} /
     *                                             403 {@code session_forbidden}
     */
    SpeakingSession getForUser(String sessionId, Long userId);

    /**
     * 列出指定用户的所有会话，按创建时间倒序。
     */
    List<SpeakingSession> listForUser(Long userId);

    /**
     * 结束会话：把 status 置为 finished，写入 summary，同步更新 user_ability_profile。
     */
    SpeakingSession finish(Long userId, String sessionId, SessionDtos.FinishSessionRequest request);

    /**
     * 把会话实体映射成对外响应的 DTO（不暴露数据库内部字段）。
     *
     * @param s 会话实体
     * @return 对外响应
     */
    SessionDtos.SessionResponse toResponse(SpeakingSession s);
}
