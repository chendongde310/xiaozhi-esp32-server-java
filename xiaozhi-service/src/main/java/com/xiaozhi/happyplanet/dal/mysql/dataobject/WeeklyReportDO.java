package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 星球周报归档：每周日定时生成并落库，供历史回看与情绪数据分析。
 * 当周滚动预览走实时计算不落库；此表仅存已完成的自然周报告，
 * 按 (deviceId, roleId, periodStart) 唯一。
 */
@Data
@TableName("sys_weekly_report")
public class WeeklyReportDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private Integer energyStart;
    private Integer energyEnd;
    private Integer energyAvg;
    private Integer energyMin;
    private Integer energyMax;
    /** 每日能量点（JSON int 数组） */
    private String energyCurve;
    private Integer tasksDone;
    private Integer streakDays;
    private Integer companionDays;
    private Integer stage;
    private Integer badgesEarned;
    /** 本周新记住的档案（JSON 数组 [{label,value}]） */
    private String newMemories;
    /** 本周专属寄语（逐字台词） */
    private String highlight;
    private LocalDateTime createTime;
}
