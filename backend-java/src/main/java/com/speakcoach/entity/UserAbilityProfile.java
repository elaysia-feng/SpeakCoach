// `user_ability_profile` 表的 MyBatis-Plus 实体 —— 每个用户的聚合长期画像。
package com.speakcoach.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
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
@TableName("user_ability_profile")
public class UserAbilityProfile {

    @TableId(value = "user_id")
    private Long userId;

    @TableField(value = "grammar_score")
    private Integer grammarScore;

    @TableField(value = "vocabulary_score")
    private Integer vocabularyScore;

    @TableField(value = "fluency_score")
    private Integer fluencyScore;

    @TableField(value = "logic_score")
    private Integer logicScore;

    /** JSON 列 —— 以字符串形式存储；不在 JDBC 层解析。 */
    @TableField(value = "common_errors")
    private String commonErrors;

    /** CEFR 等级（A1-C2）—— 由 M2-F 写入；M1-A 阶段保持 null。 */
    @TableField(value = "cefr_level")
    private String cefrLevel;

    /** 最近一次 CEFR 评估时间 —— 由 M2-F 写入。 */
    @TableField(value = "last_assessed_at")
    private LocalDateTime lastAssessedAt;

    /** 连续打卡天数 —— 由 M2-E 维护。 */
    @TableField(value = "streak_count")
    private Integer streakCount;

    /** 数据库侧管理的时间戳；不通过 Java 写入。 */
    @TableField(value = "updated_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime updatedAt;
}
