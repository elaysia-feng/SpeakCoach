// `speaking_turn` 表的 MyBatis-Plus 实体 —— 每次用户/AI 交互对应一行。
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
@TableName("speaking_turn")
public class SpeakingTurn {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "session_id")
    private String sessionId;

    @TableField(value = "turn_id")
    private Integer turnId;

    @TableField(value = "user_text")
    private String userText;

    @TableField(value = "user_audio_url")
    private String userAudioUrl;

    @TableField(value = "ai_reply")
    private String aiReply;

    @TableField(value = "audio_url")
    private String audioUrl;

    /** JSON 列 —— 以字符串形式存储；不在 JDBC 层解析。 */
    @TableField(value = "corrections")
    private String corrections;

    @TableField(value = "shadow_answer")
    private String shadowAnswer;

    /** JSON 列 —— 以字符串形式存储；不在 JDBC 层解析。 */
    @TableField(value = "ability_score")
    private String abilityScore;

    /** JSON 列 —— WhisperX / 语音侧统计指标。 */
    @TableField(value = "speech_metrics")
    private String speechMetrics;

    /** JSON 列 —— 单词级时间戳数组。 */
    @TableField(value = "word_timestamps")
    private String wordTimestamps;

    @TableField(value = "strategy")
    private String strategy;

    @TableField(value = "checkpoint_id")
    private String checkpointId;

    /**
     * 回合生命周期标记。当前写入的唯一值是 {@code "active"} —— 该字段
     * 预留给将来可能出现的"软删除 / 归档 / 失败"状态，属于
     * {@code speaking_turn} 行契约的一部分；下游仪表盘与审计管线已按其过滤。
     * 未经协调 DDL 变更，请勿移除。
     */
    @TableField(value = "status")
    private String status;

    /** 数据库侧管理的时间戳；不通过 Java 写入。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
