// `audit_log` 表的 MyBatis-Plus 实体 —— 每个节点的追踪记录。
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
@TableName("audit_log")
public class AuditLog {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "user_id")
    private Long userId;

    @TableField(value = "session_id")
    private String sessionId;

    @TableField(value = "turn_id")
    private Integer turnId;

    @TableField(value = "node_name")
    private String nodeName;

    /** JSON 列 —— 以字符串形式存储；不在 JDBC 层解析。 */
    @TableField(value = "input_json")
    private String inputJson;

    /** JSON 列 —— 以字符串形式存储；不在 JDBC 层解析。 */
    @TableField(value = "output_json")
    private String outputJson;

    @TableField(value = "latency_ms")
    private Long latencyMs;

    @TableField(value = "model_name")
    private String modelName;

    @TableField(value = "prompt_version")
    private String promptVersion;

    /** 数据库侧管理的时间戳；不通过 Java 写入。 */
    @TableField(value = "created_at", insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
