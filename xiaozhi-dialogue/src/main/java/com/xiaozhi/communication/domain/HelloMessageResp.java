package com.xiaozhi.communication.domain;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class HelloMessageResp {
    private String type = "hello";
    private String transport;
    private String sessionId;
    private AudioParams audioParams;
    /** 当前设备的“待命主动搭话”开关，供设备同步本地自我唤醒策略（snake_case: proactive_enabled）。 */
    private Boolean proactiveEnabled;
}
