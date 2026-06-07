// UserAbilityHistoryService — M1-A 长期记忆里程碑。
// 接口先行：先把契约钉在 controller 之前，impl 再补上。
package com.speakcoach.service;

import com.speakcoach.dto.AuthDtos;
import com.speakcoach.entity.UserAbilityProfile;

import java.util.List;

/**
 * 用户能力历史快照服务
 * <ul>
 *   <li>{@link #snapshot(Long, String, Integer, UserAbilityProfile)} —— 在每次 turn 之后
 *       把当前 {@code user_ability_profile} 写入 {@code user_ability_history}；
 *       失败由 impl 内部 try/catch 吞掉，绝不破坏主链路。</li>
 *   <li>{@link #snapshotFromRequest(AuthDtos.AbilityHistorySnapshotRequest)} —— 给 Python
 *       /api/internal/profile/snapshot 端点用，失败同样旁路。</li>
 *   <li>{@link #getTimeline(Long, int)} —— 拉取最近 N 条快照，给 Profile 页用。</li>
 * </ul>
 */
public interface UserAbilityHistoryService {

    /**
     * 把传入的画像实体序列化为 JSON 并写入历史表。
     *
     * @param userId    触发快照的用户 ID
     * @param sessionId 当前会话 ID（可为 null）
     * @param turnId    当前 turn 索引（可为 null）
     * @param profile   当下的画像实体（不含 cefr / streak 字段时按 null 处理）
     * @return 写入成功时返回 true；任何异常被吞掉，返回 false
     */
    boolean snapshot(Long userId, String sessionId, Integer turnId, UserAbilityProfile profile);

    /**
     * Python 侧 save_report 节点调用此方法写入本轮评分快照。
     * 同样保证失败旁路 —— 不允许阻断 AI 主链。
     */
    boolean snapshotFromRequest(AuthDtos.AbilityHistorySnapshotRequest request);

    /**
     * 拉取某用户最近 {@code limit} 条快照，按 {@code created_at} 倒序。
     * 内部 limit 限制为 [1, 100]。
     */
    List<AuthDtos.AbilityHistoryEntry> getTimeline(Long userId, int limit);
}
