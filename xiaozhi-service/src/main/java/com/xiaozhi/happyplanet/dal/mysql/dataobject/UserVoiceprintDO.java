package com.xiaozhi.happyplanet.dal.mysql.dataobject;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户声纹特征映射：每个玩家账号（userId）最多一条（一人一声纹，userId 唯一）。
 *
 * <p>对应讯飞声纹（新版）里 group 下的一个 feature：{@link #groupId} + {@link #featureId}
 * 唯一定位一条声纹特征。设备语音每轮用当轮 PCM 对该 featureId 做 1:1 比对判定说话人身份。
 */
@Data
@TableName("sys_user_voiceprint")
public class UserVoiceprintDO {

    public static final int STATUS_INVALID = 0;
    public static final int STATUS_ACTIVE = 1;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 玩家账号ID（唯一，一人一声纹）。 */
    private Integer userId;

    /** 讯飞声纹特征库标识。 */
    private String groupId;

    /** 讯飞声纹特征标识（形如 u{userId}）。 */
    private String featureId;

    /** 录入音频采样率（默认 16000）。 */
    private Integer sampleRate;

    /** 录入样本音频存储路径（可空）。 */
    private String audioPath;

    /** 状态：0-无效 1-有效。 */
    private Integer status;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
