package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 星球徽章（连击 / 阶段 / 纪念日成就），按 (deviceId, roleId, badgeKey) 唯一。
 * badgeKey 由代码侧 {@code BadgeCatalog} 约束，唯一键保证授予幂等。
 */
@Data
@TableName("sys_agent_badge")
public class AgentBadgeDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;
    /** 徽章键（代码侧目录约束） */
    private String badgeKey;
    private LocalDate earnDate;
    private LocalDateTime createTime;
}
