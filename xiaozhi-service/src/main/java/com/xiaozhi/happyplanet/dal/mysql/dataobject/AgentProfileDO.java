package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 快乐星球档案（结构化长期记忆，键值对），按 (deviceId, roleId, fieldKey) 唯一。
 * fieldKey 受服务层白名单约束，避免记录敏感信息。
 */
@Data
@TableName("sys_agent_profile")
public class AgentProfileDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;
    private Integer userId;
    private String fieldKey;
    private String fieldValue;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
