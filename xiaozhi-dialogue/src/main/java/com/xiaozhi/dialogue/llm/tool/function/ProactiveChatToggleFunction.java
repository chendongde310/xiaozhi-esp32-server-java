package com.xiaozhi.dialogue.llm.tool.function;

import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.llm.tool.XiaozhiToolMetadata;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.dialogue.runtime.Persona;
import com.xiaozhi.happyplanet.service.ProactiveConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语音开关“待命主动搭话”。用户说“以后别主动找我了 / 不要主动跟我说话”时关闭；
 * 说“以后可以主动找我 / 你可以主动来找我聊天”时开启。落库到 sys_proactive_config，立即生效。
 */
@Slf4j
@Component
public class ProactiveChatToggleFunction implements ToolsGlobalRegistry.GlobalFunction {

    private static final String TOOL_NAME = "toggle_proactive_chat";

    @Resource
    private SessionManager sessionManager;
    @Resource
    private ProactiveConfigService proactiveConfigService;

    ToolCallback toolCallback = FunctionToolCallback
            .builder(TOOL_NAME, (Map<String, Object> params, ToolContext toolContext) -> {
                String sessionId = (String) toolContext.getContext().get(Persona.TOOL_CONTEXT_SESSION_ID_KEY);
                ChatSession chatSession = sessionManager.getSession(sessionId);
                if (chatSession == null || chatSession.getDevice() == null
                        || chatSession.getDevice().getRoleId() == null) {
                    return "这个设置暂时改不了，等下再试试。";
                }
                Boolean enable = parseBool(params.get("enable"));
                if (enable == null) {
                    return "你是想让我以后可以主动找你，还是不要主动打扰你呢？";
                }
                DeviceBO device = chatSession.getDevice();
                try {
                    proactiveConfigService.setEnabled(device.getDeviceId(), device.getRoleId(), enable);
                    return enable
                            ? "好呀，那我以后想你的时候，会主动来找你聊两句。你随时可以再让我安静下来。"
                            : "好的，我以后就不主动打扰你啦，需要我的时候你叫我一声就行。";
                } catch (Exception e) {
                    log.error("切换主动搭话开关异常，enable={}", enable, e);
                    return "这个设置暂时改不了，等下再试试。";
                }
            })
            .toolMetadata(new XiaozhiToolMetadata(true))
            .description("当用户表达“是否希望设备在待命时主动找他说话”的意愿时调用，用于开启或关闭“待命主动搭话”。"
                    + "用户说“以后别主动找我了/不要主动跟我说话/别老是主动开口”时 enable=false；"
                    + "说“以后可以主动找我/你可以主动来找我聊天/想我了就叫我”时 enable=true。")
            .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "enable": { "type": "boolean", "description": "true=允许主动搭话；false=关闭主动搭话" }
                        },
                        "required": ["enable"]
                    }
                """)
            .inputType(Map.class)
            .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
            .build();

    private Boolean parseBool(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(raw).trim().toLowerCase();
        if (s.isEmpty()) {
            return null;
        }
        if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("on")
                || s.equals("开") || s.equals("开启") || s.equals("是")) {
            return Boolean.TRUE;
        }
        if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("off")
                || s.equals("关") || s.equals("关闭") || s.equals("否")) {
            return Boolean.FALSE;
        }
        return null;
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
        return "开启或关闭待命主动搭话";
    }
}
