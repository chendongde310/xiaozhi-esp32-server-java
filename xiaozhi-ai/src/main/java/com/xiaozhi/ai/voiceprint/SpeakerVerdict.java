package com.xiaozhi.ai.voiceprint;

/**
 * 说话人身份判定结果（声纹 1:1 比对产物）。
 *
 * <ul>
 *   <li>{@link #OWNER}   —— 声纹匹配，判定为本机主人（正常、可开放地对话）。</li>
 *   <li>{@link #GUEST}   —— 声纹不匹配，判定为访客（角色进入「温和有边界」模式）。</li>
 *   <li>{@link #UNKNOWN} —— 无法判定：未录入声纹 / 音频过短 / 接口异常等。
 *       遵循 fail-open：一律按普通对话处理，绝不因故障把主人锁在门外。</li>
 * </ul>
 */
public enum SpeakerVerdict {
    OWNER,
    GUEST,
    UNKNOWN;

    /** 供 UserMessage 元数据/系统提示使用的中文说话人标签；UNKNOWN 不打标签（返回 null）。 */
    public String label() {
        return switch (this) {
            case OWNER -> "主人";
            case GUEST -> "访客";
            case UNKNOWN -> null;
        };
    }
}
