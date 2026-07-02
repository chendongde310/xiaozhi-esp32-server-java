package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 智能体运行时状态（快乐能量 / 陪伴天数 / 首连标记 / 当前频道），按 (deviceId, roleId) 唯一。
 * 时间戳交由数据库默认值与 ON UPDATE 维护，DO 不使用自动填充。
 */
@Data
@TableName("sys_agent_state")
public class AgentStateDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;
    /** 快乐能量 0-100 */
    private Integer energy;
    private LocalDateTime energyUpdateTime;
    /** 累计陪伴天数 */
    private Integer companionDays;
    private LocalDate firstInteractDate;
    private LocalDate lastInteractDate;
    /** 是否已完成首连仪式：0-否 1-是 */
    private Integer firstConnected;
    /** 当前频道：day/night/childhood/energy_repair */
    private String currentChannel;
    private LocalDate lastTaskDate;
    private Long currentTaskId;
    /** 今日任务是否完成：0-否 1-是 */
    private Integer currentTaskDone;

    // —— 成长体系（V14）——
    /** 当前连续完成每日任务的天数 */
    private Integer streakDays;
    /** 历史最长连击 */
    private Integer bestStreak;
    /** 最近一次让连击 +1 的日期（判断连续/断签） */
    private LocalDate lastStreakDate;
    /** 剩余能量修复卡（补签卡）张数 */
    private Integer streakRepairLeft;
    /** 补签卡发放所属月份（yyyy-MM），跨月自动补充 */
    private String streakRepairMonth;
    /** 最近一次已庆祝到的羁绊阶段：1 初识 / 2 熟悉 / 3 老朋友 / 4 星球密友（仅用于仪式去重） */
    private Integer stage;
    /** 最近一次已庆祝的陪伴里程碑天数（7/30/100/365，仅用于纪念仪式去重） */
    private Integer lastAnniversary;

    // —— 待命主动搭话运行时护栏（V15）——
    /** 最近一次主动搭话时间（用于冷却判定） */
    private LocalDateTime lastProactiveTime;
    /** 主动搭话计数所属日期（跨天自动归零） */
    private LocalDate proactiveDate;
    /** 当日已主动搭话次数（对照 dailyLimit） */
    private Integer proactiveCount;
    /** 当日主动搭话是否被忽略：1=已被忽略，当天退避不再主动 */
    private Integer proactiveIgnoredToday;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
