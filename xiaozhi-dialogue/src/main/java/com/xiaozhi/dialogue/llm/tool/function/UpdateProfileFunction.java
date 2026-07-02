package com.xiaozhi.dialogue.llm.tool.function;

import com.xiaozhi.ai.llm.tool.ToolCallStringResultConverter;
import com.xiaozhi.ai.llm.tool.XiaozhiToolMetadata;
import com.xiaozhi.ai.tool.ToolsGlobalRegistry;
import com.xiaozhi.ai.tool.session.ToolSession;
import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.dialogue.runtime.Persona;
import com.xiaozhi.happyplanet.service.AgentProfileService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 更新快乐星球档案（结构化长期记忆）。字段键受服务层白名单约束，从代码层面拒绝敏感信息。
 */
@Slf4j
@Component
public class UpdateProfileFunction implements ToolsGlobalRegistry.GlobalFunction {

    private static final String TOOL_NAME = "update_profile";

    @Resource
    private SessionManager sessionManager;
    @Resource
    private AgentProfileService agentProfileService;

    ToolCallback toolCallback = FunctionToolCallback
            // 用 Map<String,Object>：字段值可能被模型作为非字符串下发，避免 String 强转抛 CCE
            .builder(TOOL_NAME, (Map<String, Object> params, ToolContext toolContext) -> {
                String sessionId = (String) toolContext.getContext().get(Persona.TOOL_CONTEXT_SESSION_ID_KEY);
                ChatSession chatSession = sessionManager.getSession(sessionId);
                if (chatSession == null || chatSession.getDevice() == null) {
                    return "我这边好像没连上，等一下再帮你记好吗？";
                }
                String field = asStr(params.get("field"));
                String value = asStr(params.get("value"));
                DeviceBO device = chatSession.getDevice();
                try {
                    agentProfileService.updateField(device.getUserId(), device.getDeviceId(),
                            device.getRoleId(), field, value);
                    String label = AgentProfileService.PROFILE_LABELS.getOrDefault(field, field);
                    return "好的，我把你的" + label + "记在快乐星球档案里啦。";
                } catch (IllegalArgumentException e) {
                    // 区分：非白名单字段（住址/证件等）→ 按内容安全边界温和拒绝；其余（如空值）→ 请对方再说一次
                    if (!agentProfileService.isAllowedKey(field)) {
                        return "这类信息我就不特别记录啦，我们聊点别的吧。";
                    }
                    return "刚才没听清，等会儿你再告诉我一次好吗？";
                } catch (Exception e) {
                    log.error("更新快乐星球档案异常，field={}, value={}", field, value, e);
                    return "刚才没记住，等会儿你再告诉我一次好吗？";
                }
            })
            .toolMetadata(new XiaozhiToolMetadata(true))
            .description("当用户明确告诉你希望长期记住的个人偏好时调用，用于更新快乐星球档案。"
                    + "仅可记录以下字段：nickname(昵称)、preferredCall(喜欢的称呼)、storyType(喜欢的故事类型)、"
                    + "storyLength(short短/long长 故事偏好)、companionStyle(quiet安静/lively活泼 陪伴风格)、"
                    + "activeTime(常用互动时间)、importantDate(重要日期)。"
                    + "严禁记录住址、证件、账号密码、财务等敏感信息。")
            .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "field": { "type": "string", "description": "档案字段键，只能是 nickname/preferredCall/storyType/storyLength/companionStyle/activeTime/importantDate 之一" },
                            "value": { "type": "string", "description": "字段对应的值" }
                        },
                        "required": ["field", "value"]
                    }
                """)
            .inputType(Map.class)
            .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
            .build();

    private static String asStr(Object o) {
        return o == null ? null : String.valueOf(o);
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
        return "更新快乐星球档案";
    }
}
