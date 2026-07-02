package com.xiaozhi.dialogue.llm.tool.function;

import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.llm.tool.XiaozhiToolMetadata;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.dialogue.runtime.Persona;
import com.xiaozhi.happyplanet.service.PlayerAgentService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class CreateAppLinkCodeFunction implements ToolsGlobalRegistry.GlobalFunction {

    private static final String TOOL_NAME = "create_app_link_code";

    @Resource
    private SessionManager sessionManager;
    @Resource
    private PlayerAgentService playerAgentService;

    private final ToolCallback toolCallback = FunctionToolCallback
            .builder(TOOL_NAME, (Map<String, Object> params, ToolContext toolContext) -> {
                String sessionId = (String) toolContext.getContext().get(Persona.TOOL_CONTEXT_SESSION_ID_KEY);
                ChatSession chatSession = sessionManager.getSession(sessionId);
                if (chatSession == null || chatSession.getDevice() == null) {
                    return "我现在还没有连上设备，等一下再试试绑定 APP。";
                }
                DeviceBO device = chatSession.getDevice();
                try {
                    PlayerAgentService.AgentLinkCode code =
                            playerAgentService.createLinkCode(device.getDeviceId(), device.getRoleId());
                    return linkCodeSpeech(code.code());
                } catch (Exception e) {
                    log.warn("生成 APP 星球码失败 deviceId={}, roleId={}",
                            device.getDeviceId(), device.getRoleId(), e);
                    return "星球码暂时生成失败，等一下你再对我说一遍绑定 APP。";
                }
            })
            .toolMetadata(new XiaozhiToolMetadata(true))
            .description("当用户明确要求绑定 APP、关联 APP、连接 APP、生成星球码、查看星球码，或说类似“我要在手机上绑定你”时必须调用。"
                    + "该工具会为当前固件设备生成一次性的玩家 APP 星球码，并直接把星球码读给用户。")
            .inputSchema("""
                    {
                      "type": "object",
                      "properties": {}
                    }
                    """)
            .inputType(Map.class)
            .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
            .build();

    public static String linkCodeSpeech(String code) {
        String spoken = code == null ? "" : String.join(" ", code.split(""));
        return "可以。你的 APP 星球码是 " + spoken
                + "。十分钟内打开 APP，在我的 AI 页面输入这串数字，就能关联我。";
    }

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
        return "生成玩家 APP 星球码";
    }
}
