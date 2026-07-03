package com.xiaozhi.ai.voiceprint;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.UserVoiceprintDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.UserVoiceprintMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 声纹识别编排服务：录入 / 删除 / 状态 / 说话人 1:1 比对，并维护「一人一声纹」DB 映射。
 *
 * <p>录入：同一 userId 固定 featureId=`u{userId}`，重复录入先删旧特征再建，天然「唯一一个」。
 * <p>比对：{@link #verify} 严格 fail-open —— 未启用/未录入/音频过短/接口异常一律 {@link SpeakerVerdict#UNKNOWN}。
 */
@Slf4j
@Service
public class VoiceprintService {

    /** 16k 单声道 16bit：每毫秒 16000*2/1000 = 32 字节。 */
    private static final int BYTES_PER_MS = 32;

    @Resource
    private VoiceprintProperties props;
    @Resource
    private XfyunVoiceprintClient client;
    @Resource
    private UserVoiceprintMapper mapper;

    /** 声纹功能是否可用（开关开启且凭证齐全）。 */
    public boolean isEnabled() {
        return props.isUsable();
    }

    public double threshold() {
        return props.getThreshold();
    }

    /** 查询某用户的声纹映射（无则 null）。 */
    public UserVoiceprintDO get(Integer userId) {
        if (userId == null) {
            return null;
        }
        return mapper.selectOne(new LambdaQueryWrapper<UserVoiceprintDO>()
                .eq(UserVoiceprintDO::getUserId, userId)
                .eq(UserVoiceprintDO::getStatus, UserVoiceprintDO.STATUS_ACTIVE)
                .last("limit 1"));
    }

    public boolean isEnrolled(Integer userId) {
        return get(userId) != null;
    }

    /**
     * 录入 / 重录当前用户声纹（一人一声纹：先删旧特征，再建新特征，落库）。
     *
     * @param userId    玩家账号ID
     * @param pcm16k    16k/16bit/单声道 raw PCM
     * @param audioPath 录入样本存储路径（可空）
     * @throws IllegalStateException    功能未启用
     * @throws IllegalArgumentException 音频过短
     */
    public UserVoiceprintDO enroll(Integer userId, byte[] pcm16k, String audioPath) {
        if (!isEnabled()) {
            throw new IllegalStateException("声纹功能未启用");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空");
        }
        assertAudioLongEnough(pcm16k);

        String featureId = featureIdOf(userId);
        UserVoiceprintDO existing = get(userId);
        // 首次录入用 createFeature；重录用 updateFeature(cover=true) 得到干净的「替换」语义，
        // 避免讯飞「刚建即删」的一致性滞后。重录时若特征已不存在（库被清/滞后）则退回创建。
        if (existing != null) {
            try {
                client.updateFeature(featureId, pcm16k);
            } catch (XfyunVoiceprintClient.VoiceprintApiException e) {
                log.info("重录 updateFeature 失败，退回 createFeature: userId={}, err={}", userId, e.getMessage());
                client.createFeature(featureId, pcm16k);
            }
        } else {
            client.createFeature(featureId, pcm16k);
        }

        UserVoiceprintDO row = existing != null ? existing : new UserVoiceprintDO();
        row.setUserId(userId);
        row.setGroupId(props.getGroupId());
        row.setFeatureId(featureId);
        row.setSampleRate(16000);
        row.setAudioPath(audioPath);
        row.setStatus(UserVoiceprintDO.STATUS_ACTIVE);
        if (existing != null) {
            mapper.updateById(row);
        } else {
            mapper.insert(row);
        }
        log.info("声纹录入成功: userId={}, featureId={}", userId, featureId);
        return row;
    }

    /**
     * 删除当前用户声纹（讯飞侧删特征 + 删库行）。
     *
     * @return 之前是否存在声纹
     */
    public boolean delete(Integer userId) {
        UserVoiceprintDO row = get(userId);
        if (row == null) {
            return false;
        }
        try {
            client.deleteFeature(row.getFeatureId());
        } catch (Exception e) {
            log.warn("删除讯飞声纹特征失败（仍将删库）: userId={}, err={}", userId, e.getMessage());
        }
        mapper.deleteById(row.getId());
        log.info("声纹删除成功: userId={}, featureId={}", userId, row.getFeatureId());
        return true;
    }

    /**
     * 说话人 1:1 比对。fail-open：任何不确定情形返回 {@link SpeakerVerdict#UNKNOWN}。
     *
     * @param featureId 目标特征（该用户已录入的声纹）
     * @param pcm16k    当轮音频
     */
    public SpeakerVerdict verify(String featureId, byte[] pcm16k) {
        if (!isEnabled() || featureId == null || pcm16k == null
                || pcm16k.length < minBytes()) {
            return SpeakerVerdict.UNKNOWN;
        }
        try {
            double score = client.searchScoreFea(featureId, pcm16k);
            SpeakerVerdict verdict = score >= props.getThreshold() ? SpeakerVerdict.OWNER : SpeakerVerdict.GUEST;
            log.info("声纹比对: featureId={}, score={}, 阈值={}, 判定={}",
                    featureId, score, props.getThreshold(), verdict);
            return verdict;
        } catch (Exception e) {
            log.warn("声纹比对异常，按 UNKNOWN 处理: featureId={}, err={}", featureId, e.getMessage());
            return SpeakerVerdict.UNKNOWN;
        }
    }

    public boolean isAudioLongEnough(byte[] pcm16k) {
        return pcm16k != null && pcm16k.length >= minBytes();
    }

    private void assertAudioLongEnough(byte[] pcm16k) {
        if (!isAudioLongEnough(pcm16k)) {
            throw new IllegalArgumentException(
                    "录音太短，请说满约 " + Math.max(1, props.getMinAudioMillis() / 1000) + " 秒（建议 3-5 秒）");
        }
    }

    private int minBytes() {
        return props.getMinAudioMillis() * BYTES_PER_MS;
    }

    private static String featureIdOf(Integer userId) {
        return "u" + userId;
    }
}
