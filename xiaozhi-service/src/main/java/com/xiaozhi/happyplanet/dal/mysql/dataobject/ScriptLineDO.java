package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 品牌台词库：仪式性 / 频道过场台词，逐字播报，不经过 LLM，便于甲方审核。
 */
@Data
@TableName("sys_script_lines")
public class ScriptLineDO {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String sceneKey;
    private Integer seq;
    private String content;
    private String emotion;
    private Integer enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
