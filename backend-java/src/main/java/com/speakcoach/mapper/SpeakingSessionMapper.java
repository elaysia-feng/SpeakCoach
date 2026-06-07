// SpeakingSession 实体的 MyBatis-Plus Mapper。
package com.speakcoach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.speakcoach.entity.SpeakingSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SpeakingSessionMapper extends BaseMapper<SpeakingSession> {

    /** 某用户的全部会话，按创建时间倒序。 */
    @Select("SELECT * FROM speaking_session WHERE user_id = #{userId} ORDER BY created_at DESC")
    List<SpeakingSession> selectByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    /** 带归属权校验的单条查询 —— 合并为一条 SQL，避免 service 层多一次往返。 */
    @Select("SELECT * FROM speaking_session WHERE id = #{id} AND user_id = #{userId} LIMIT 1")
    SpeakingSession selectByIdAndUserId(@Param("id") String id, @Param("userId") Long userId);

    /** 预留下一 turnId 时锁定该会话行。 */
    @Select("SELECT * FROM speaking_session WHERE id = #{id} AND user_id = #{userId} LIMIT 1 FOR UPDATE")
    SpeakingSession selectByIdAndUserIdForUpdate(@Param("id") String id, @Param("userId") Long userId);
}
