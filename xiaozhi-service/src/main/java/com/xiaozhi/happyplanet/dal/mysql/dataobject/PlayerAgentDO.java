package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 玩家账号与控制台 AI 实例的关联。
 */
@Data
@TableName("sys_player_agent")
public class PlayerAgentDO {

    public static final String STATE_ACTIVE = "1";
    public static final String STATE_INACTIVE = "0";

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Integer userId;
    private String deviceId;
    private Integer roleId;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
