package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 星球任务池：每日轻量互动任务，非学习打卡、非强制。
 */
@Data
@TableName("sys_planet_task")
public class PlanetTaskDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String category;
    private String content;
    private String audience;
    private Integer enabled;

    // —— 主题任务链（V18）——
    /** 主题链键（空=独立单条任务，既有行为不变） */
    private String chainKey;
    /** 主题链内序号（1..N，从 1 开始） */
    private Integer chainSeq;
    /** 主题链标题（如“地球观察周”） */
    private String chainTitle;

    private LocalDateTime createTime;
}
