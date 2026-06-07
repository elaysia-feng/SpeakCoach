// User 实体的 MyBatis-Plus Mapper —— 用户名/邮箱查询与存在性校验。
package com.speakcoach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.speakcoach.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    @Select("SELECT * FROM user WHERE username = #{username} LIMIT 1")
    User selectByUsername(@Param("username") String username);

    @Select("SELECT * FROM user WHERE email = #{email} LIMIT 1")
    User selectByEmail(@Param("email") String email);

    /**
     * 返回匹配 {@code username} 或 {@code email}（取首个非空且命中的）的用户。
     * 用于在登录时将 "先 username 再 email" 的模式合并为单条查询。
     * 两个参数都可选；null 会被合并为一个永不匹配的值。
     */
    @Select("SELECT * FROM user " +
            "WHERE (username = COALESCE(#{username}, username) AND #{username} IS NOT NULL) " +
            "   OR (email = COALESCE(#{email}, email) AND #{email} IS NOT NULL) " +
            "LIMIT 1")
    User selectByUsernameOrEmail(@Param("username") String username, @Param("email") String email);

    @Select("SELECT COUNT(1) > 0 FROM user WHERE username = #{username}")
    boolean existsByUsername(@Param("username") String username);

    @Select("SELECT COUNT(1) > 0 FROM user WHERE email = #{email}")
    boolean existsByEmail(@Param("email") String email);

    /**
     * 部分更新 user 偏好（M1-B）。
     * 任意一个参数为 null 则该列不变 —— 利用 SQL 的 {@code IFNULL(#{...}, column)}
     * 表达"保留原值"。MySQL 的 {@code IFNULL(a, b)} 在 a 不为 NULL 时返回 a，
     * 否则返回 b —— 正是我们想要的"默认值为当前列"语义。
     */
    @Select("UPDATE user SET " +
            "coach_persona   = IFNULL(#{coachPersona},   coach_persona), " +
            "preferred_voice = IFNULL(#{preferredVoice}, preferred_voice) " +
            "WHERE id = #{userId}")
    int updatePreferences(@Param("userId") Long userId,
                          @Param("coachPersona") String coachPersona,
                          @Param("preferredVoice") String preferredVoice);
}
