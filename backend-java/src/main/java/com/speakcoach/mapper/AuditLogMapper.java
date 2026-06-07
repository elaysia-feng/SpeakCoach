// AuditLog 实体的 MyBatis-Plus Mapper —— 由 AuditService 使用。
package com.speakcoach.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.speakcoach.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
