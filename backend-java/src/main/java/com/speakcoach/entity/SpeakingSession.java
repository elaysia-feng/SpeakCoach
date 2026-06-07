// `speaking_session` 表的 MyBatis-Plus 实体 —— 每个会话对应一行。
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
@TableName("speaking_session")
public class SpeakingSession {

    @TableId(value = "id")
    private String id;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "scene")
    private String scene;

    @TableField(value = "status")
    private String status;

    @TableField(value = "turn_count")
    private Integer turnCount;

    /** JSON 列 —— 以字符串形式存储；不在 JDBC 层解析。 */
    @TableField(value = "summary")
    private String summary;

    /** 数据库侧管理的时间戳；不通过 Java 写入。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;

    /** 由 service 在 insert/update 时写入（非 MP 自动填充）。 */
    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
