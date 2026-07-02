package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 待命主动搭话配置，按 (deviceId, roleId) 唯一。全部字段为“可控变量”，默认适中；
 * 总开关 {@link #enabled} 默认关闭——无对应行时代码一律按关闭处理。
 *
 * <p>本表只承载“策略参数”；主动搭话的运行时护栏计数（次数/冷却/退避）挂在 sys_agent_state 上。
 */
@Data
@TableName("sys_proactive_config")
public class ProactiveConfigDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;

    /** 主动搭话总开关：0-关 1-开（默认关）。 */
    private Integer enabled;
    /** 是否允许 LLM 增强开场：0-仅脚本台词 1-脚本为主+LLM增强。 */
    private Integer allowLlm;
    /** 每日主动搭话次数上限。 */
    private Integer dailyLimit;
    /** 两次主动之间的最小冷却分钟数。 */
    private Integer cooldownMinutes;
    /** 设备须先静默待命多久才可主动（秒）。 */
    private Integer minIdleSeconds;
    /** 活跃时段开始（此段内才考虑主动）。 */
    private LocalTime activeStart;
    /** 活跃时段结束。 */
    private LocalTime activeEnd;
    /** 硬静默时段开始（绝不主动，优先级高于活跃时段）。 */
    private LocalTime quietStart;
    /** 硬静默时段结束（跨零点）。 */
    private LocalTime quietEnd;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
