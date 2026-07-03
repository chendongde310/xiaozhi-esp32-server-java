package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成长状态提示词槽位覆盖，按 (roleId, slotKey) 唯一。
 *
 * <p>{@code roleId=0} 表示全局默认（对所有未单独覆盖的角色生效）；{@code roleId>0} 为角色自定义。
 * 未在本表出现的槽位一律回退到 {@link com.xiaozhi.happyplanet.service.GrowthPromptSlot} 的代码内置默认。
 */
@Data
@TableName("sys_growth_prompt")
public class GrowthPromptDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 角色ID；0=全局默认 */
    private Integer roleId;
    /** 槽位键，对应 GrowthPromptSlot 枚举 */
    private String slotKey;
    /** 提示词文案，支持 {{变量}} 占位 */
    private String content;
    /** 是否启用：0-显式关闭此槽位 1-启用 */
    private Integer enabled;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
