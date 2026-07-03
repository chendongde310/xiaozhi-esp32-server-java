package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 星球任务行为账本：每一次“分配 / 完成 / 观察回音”都留一行。
 *
 * <p>是任务去重、地球观察日志、周报任务明细、行为画像四件事的共同底座。
 * taskContent/category 为快照冗余：即使任务从池中删除，账本与日志仍可读。
 */
@Data
@TableName("sys_task_log")
public class TaskLogDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String deviceId;
    private Integer roleId;
    /** 关联 sys_planet_task.id（任务被删仍保留下方快照） */
    private Long taskId;
    /** 任务正文快照 */
    private String taskContent;
    /** 任务分类快照：self/observation/family */
    private String category;
    /** 所属主题链键（非链任务为空） */
    private String chainKey;
    /** 主题链内序号 */
    private Integer chainSeq;
    /** 分配/送达来源：scheduler/chat/proactive */
    private String source;
    /** 状态：assigned/done */
    private String status;
    /** 用户观察回音（完成时口述的发现，透明可删） */
    private String echo;
    /** 观察拍照记录 URL（带摄像头设备可选） */
    private String echoImageUrl;
    /** 分配日期（用于去重与日志按天归集） */
    private LocalDate assignDate;
    /** 完成时间 */
    private LocalDateTime doneTime;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
