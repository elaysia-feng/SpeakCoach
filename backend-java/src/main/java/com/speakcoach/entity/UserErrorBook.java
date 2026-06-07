// `user_error_book` 表的 MyBatis-Plus 实体 —— 用户错题本。
// 每个 (user_id, original, corrected) 三元组由 UNIQUE 约束保证去重。
// mastery_level: 0=new, 1=learning, 2=reviewing, 3=mastered。
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
@TableName("user_error_book")
public class UserErrorBook {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "type")
    private String type;

    @TableField(value = "original")
    private String original;

    @TableField(value = "corrected")
    private String corrected;

    @TableField(value = "explanation")
    private String explanation;

    @TableField(value = "source_session_id")
    private String sourceSessionId;

    @TableField(value = "source_turn_id")
    private Integer sourceTurnId;

    @TableField(value = "mastery_level")
    private Integer masteryLevel;

    @TableField(value = "next_review_at")
    private LocalDateTime nextReviewAt;

    @TableField(value = "review_count")
    private Integer reviewCount;

    /** 数据库侧管理的时间戳；不通过 Java 写入。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 由 service 在 update 时写入（非 MP 自动填充）。 */
    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
