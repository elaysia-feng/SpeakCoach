// UserErrorBook 实体的 MyBatis-Plus Mapper —— 错题本查询入口。
package com.speakcoach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.speakcoach.entity.UserErrorBook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserErrorBookMapper extends BaseMapper<UserErrorBook> {

    /**
     * 按 (user, mastery, type) 过滤并按 next_review_at ASC / created_at DESC 排序的错题列表。
     * 任意筛选条件为 null 时跳过对应条件。limit=0 表示无限制。
     */
    @Select({
        "<script>",
        "SELECT * FROM user_error_book",
        "WHERE user_id = #{userId}",
        "<if test='masteryLevel != null'> AND mastery_level = #{masteryLevel} </if>",
        "<if test='type != null and type != \"\"'> AND type = #{type} </if>",
        "ORDER BY (next_review_at IS NULL), next_review_at ASC, created_at DESC",
        "<if test='limit != null and limit > 0'> LIMIT #{limit} </if>",
        "</script>"
    })
    List<UserErrorBook> selectForUser(@Param("userId") Long userId,
                                      @Param("masteryLevel") Integer masteryLevel,
                                      @Param("type") String type,
                                      @Param("limit") Integer limit);

    /**
     * 按 type 聚合最近 7 天的新增错题数。供 /stats 端点使用。
     */
    @Select(
        "SELECT type, COUNT(*) AS cnt " +
        "FROM user_error_book " +
        "WHERE user_id = #{userId} " +
        "  AND created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY) " +
        "GROUP BY type"
    )
    List<java.util.Map<String, Object>> countByTypeSince(@Param("userId") Long userId);
}
