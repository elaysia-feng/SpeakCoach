// UserAbilityHistory 实体的 MyBatis-Plus Mapper —— 由 UserAbilityHistoryService 使用。
// 自定义派生查询全部以 @Select 注解形式声明在接口上，遵循 §11 文档中
// “短查询用注解、长查询走 XML” 的约定。
package com.speakcoach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.speakcoach.entity.UserAbilityHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserAbilityHistoryMapper extends BaseMapper<UserAbilityHistory> {

    /**
     * 拉取某个用户最近的 {@code limit} 条快照，按 created_at DESC 排序。
     * 用于 Profile 页能力趋势展示。
     */
    @Select("SELECT * FROM user_ability_history "
            + "WHERE user_id = #{userId} "
            + "ORDER BY created_at DESC, id DESC "
            + "LIMIT #{limit}")
    List<UserAbilityHistory> selectRecentByUserId(@Param("userId") Long userId,
                                                  @Param("limit") int limit);
}
