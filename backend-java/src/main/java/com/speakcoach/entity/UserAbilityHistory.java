// `user_ability_history` 表的 MyBatis-Plus 实体 —— 每次 turn 之后对
// `user_ability_profile` 做的一次完整 JSON 快照，用于在 Profile 页
// 展示趋势以及在 strategy_router 中跨 session 复用历史 common_errors。
package com.speakcoach.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_ability_history")
public class UserAbilityHistory {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "user_id")
    private Long userId;

    /** 触发本次快照的会话 ID（可为 NULL：纯画像同步）。 */
    @TableField(value = "session_id")
    private String sessionId;

    /** 触发本次快照的会话内 turn 索引（可为 NULL）。 */
    @TableField(value = "turn_id")
    private Integer turnId;

    /** 当时 user_ability_profile 的完整 JSON 快照。 */
    @TableField(value = "snapshot_json")
    private String snapshotJson;

    /** 数据库侧管理的时间戳；不通过 Java 写入。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
