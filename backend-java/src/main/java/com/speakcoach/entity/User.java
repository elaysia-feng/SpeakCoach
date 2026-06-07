// `user` 表的 MyBatis-Plus 实体 —— 主账号行。
//
// id 为 BIGINT AUTO_INCREMENT（而非 UUID 字符串）：顺序主键可在写入时
// 让 InnoDB 的聚簇 B+ 树保持紧凑。背景可参见计划
// C:\Users\seele\.claude\plans\mighty-wobbling-rose.md。
package com.speakcoach.entity;

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
@TableName("user")
public class User {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField(value = "username")
    private String username;

    @TableField(value = "email")
    private String email;

    /** AI 教练人格：warm_strict / friendly_tutor / ielts_examiner / patient_grandma。M1-B 引入。 */
    @TableField(value = "coach_persona", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String coachPersona;

    /** TTS 音色标识：当前仅 linqian_voice，预留扩展。M1-B 引入。 */
    @TableField(value = "preferred_voice", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private String preferredVoice;

    @TableField(value = "password_hash")
    private String passwordHash;

    /** 数据库侧管理的时间戳；不通过 Java 写入（DEFAULT CURRENT_TIMESTAMP）。 */
    @TableField(value = "created_at", insertStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER, updateStrategy = com.baomidou.mybatisplus.annotation.FieldStrategy.NEVER)
    private LocalDateTime createdAt;
}
