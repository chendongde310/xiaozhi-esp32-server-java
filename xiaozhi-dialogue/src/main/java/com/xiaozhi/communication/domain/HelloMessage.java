package com.xiaozhi.communication.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public final class HelloMessage extends Message {
    public HelloMessage() {
        super("hello");
    }

    private HelloFeatures features;
    private AudioParams audioParams;

    /**
     * 唤醒来源："user"（用户唤醒/按键，默认）或 "proactive"（设备待命自我唤醒，请求服务端主动开场）。
     * 显式 @JsonProperty，因通信层 ObjectMapper 未启用全局蛇形命名。
     */
    @JsonProperty("wake_source")
    private String wakeSource;
}
