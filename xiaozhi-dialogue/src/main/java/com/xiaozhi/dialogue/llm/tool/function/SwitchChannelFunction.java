package com.xiaozhi.dialogue.llm.tool.function;

import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.llm.tool.XiaozhiToolMetadata;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.dialogue.happyplanet.HappyPlanetSpeaker;
import com.xiaozhi.dialogue.runtime.Persona;
import com.xiaozhi.happyplanet.service.AgentStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 切换快乐星球陪伴频道：夜间 / 童年 / 能量修复。
 * 更新状态后，用虚拟线程延迟播放该频道的品牌过场台词（逐字，非 LLM），确保先返回简短确认。
 */
@Slf4j
@Component
public class SwitchChannelFunction implements ToolsGlobalRegistry.GlobalFunction {

    private static final String TOOL_NAME = "switch_channel";

    private static final Set<String> VALID_CHANNELS = Set.of(
            AgentStateService.CHANNEL_NIGHT,
            AgentStateService.CHANNEL_CHILDHOOD,
            AgentStateService.CHANNEL_ENERGY_REPAIR,
            AgentStateService.CHANNEL_DAY);

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            Thread.ofVirtual().name("happy-channel-", 0).factory());

    @Resource
    private SessionManager sessionManager;
    @Resource
    private AgentStateService agentStateService;
    @Resource
    private HappyPlanetSpeaker happyPlanetSpeaker;

    ToolCallback toolCallback = FunctionToolCallback
            .builder(TOOL_NAME, (Map<String, String> params, ToolContext toolContext) -> {
                String sessionId = (String) toolContext.getContext().get(Persona.TOOL_CONTEXT_SESSION_ID_KEY);
                ChatSession chatSession = sessionManager.getSession(sessionId);
                if (chatSession == null || chatSession.getDevice() == null) {
                    return "频道切换失败";
                }
                String channel = params.get("channel");
                if (channel == null || !VALID_CHANNELS.contains(channel)) {
                    return "这个频道我还不认识哦";
                }
                DeviceBO device = chatSession.getDevice();
                try {
                    agentStateService.setCurrentChannel(device.getDeviceId(), device.getRoleId(), channel);
                    // 先返回确认，再延迟播放该频道的品牌过场台词
                    if (!AgentStateService.CHANNEL_DAY.equals(channel)) {
                        scheduler.schedule(
                                () -> happyPlanetSpeaker.speakScene(chatSession, channel),
                                80, TimeUnit.MILLISECONDS);
                    }
                    return "好的，正在为你切换频道。";
                } catch (Exception e) {
                    log.error("频道切换异常，channel={}", channel, e);
                    return "频道切换失败";
                }
            })
            .toolMetadata(new XiaozhiToolMetadata(true))
            .description("当用户想进入某个陪伴频道时调用。可选频道：night(夜间频道，睡前/放松/安静陪伴)、"
                    + "childhood(童年频道，老朋友的怀旧陪伴)、energy_repair(能量修复频道，情绪低落/疲惫时的安抚)、"
                    + "day(回到日间陪伴)。用户说“我睡不着/想放松”通常切 night；说“想找回小时候的感觉”切 childhood；"
                    + "说“今天好累/不开心”切 energy_repair。")
            .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "channel": { "type": "string", "description": "目标频道：night、childhood、energy_repair 或 day" }
                        },
                        "required": ["channel"]
                    }
                """)
            .inputType(Map.class)
            .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
            .build();

    @Override
    public ToolCallback getFunctionCallTool(ToolSession toolSession) {
        return toolCallback;
    }

    @Override
    public String getToolName() {
        return TOOL_NAME;
    }

    @Override
    public String getToolDescription() {
        return "切换陪伴频道";
    }
}
