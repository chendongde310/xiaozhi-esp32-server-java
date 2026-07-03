package com.xiaozhi.ai.voiceprint;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 讯飞声纹识别（新版）配置。凭证走环境变量覆盖（见 application.yml），便于轮换。
 *
 * <p>{@code prefix=xiaozhi.voiceprint}。默认关闭需显式开启：未配置 appId/apiKey/apiSecret 时
 * {@link #isConfigured()} 为 false，声纹功能整体降级为「不启用」（普通对话，无边界模式）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "xiaozhi.voiceprint")
public class VoiceprintProperties {

    /** 功能总开关。 */
    private boolean enabled = true;

    /** 讯飞应用 APPID。 */
    private String appId;

    /** 讯飞 APIKey。 */
    private String apiKey;

    /** 讯飞 APISecret。 */
    private String apiSecret;

    /** 声纹 WebAPI 完整地址（含服务 id）。 */
    private String apiUrl = "https://api.xf-yun.com/v1/private/s1aa729d0";

    /** 声纹服务 id（请求体 parameter 下的键，须与 apiUrl 末段一致）。 */
    private String serviceId = "s1aa729d0";

    /** 所有用户共用的声纹特征库标识（字母数字下划线，≤32）。 */
    private String groupId = "xiaozhi_voiceprint";

    /** 1:1 比对判定为「主人」的相似度阈值（官方建议 0.6~1）。 */
    private double threshold = 0.6;

    /** 参与比对/录入的最短音频时长（毫秒）；短于此不比对（讯飞要求有效帧 >0.5s）。 */
    private int minAudioMillis = 600;

    /** 是否已配置必要凭证。 */
    public boolean isConfigured() {
        return notBlank(appId) && notBlank(apiKey) && notBlank(apiSecret);
    }

    /** 功能是否可用（开关开启且凭证齐全）。 */
    public boolean isUsable() {
        return enabled && isConfigured();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
