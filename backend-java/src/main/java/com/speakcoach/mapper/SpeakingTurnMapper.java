// SpeakingTurn 实体的 MyBatis-Plus Mapper。
package com.speakcoach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.speakcoach.entity.SpeakingTurn;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SpeakingTurnMapper extends BaseMapper<SpeakingTurn> {

    /** 某会话的全部回合，按回合顺序排列。用于构建 chat 历史。 */
    @Select("SELECT * FROM speaking_turn WHERE session_id = #{sessionId} ORDER BY turn_id ASC")
    List<SpeakingTurn> selectBySessionIdOrderByTurnIdAsc(@Param("sessionId") String sessionId);

    /** 某会话的最新回合 —— 用作推算下一 turnId 的安全网。 */
    @Select("SELECT * FROM speaking_turn WHERE session_id = #{sessionId} ORDER BY turn_id DESC LIMIT 1")
    Optional<SpeakingTurn> selectTopBySessionIdOrderByTurnIdDesc(@Param("sessionId") String sessionId);

}
