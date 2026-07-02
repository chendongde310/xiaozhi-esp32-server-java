package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 能量事件流水：每次快乐能量变化追加一行，支撑“能量曲线”与情绪陪伴数据资产。
 */
@Data
@TableName("sys_energy_log")
public class EnergyLogDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;
    /** 变化后的能量值（0-100） */
    private Integer energy;
    /** 本次能量增量（可为负） */
    private Integer delta;
    /** 变化原因：interaction/task/decay/repair/manual */
    private String reason;
    /** 事件日期（用于按天聚合曲线） */
    private LocalDate logDate;
    private LocalDateTime logTime;
}
