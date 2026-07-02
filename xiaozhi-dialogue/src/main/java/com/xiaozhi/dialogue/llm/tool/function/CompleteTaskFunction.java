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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 把今日星球任务标记为已完成，给予快乐能量奖励，并结算任务连击与徽章。taskId 可选，缺省表示"完成当前任务"。
 *
 * <p>先返回简短文字确认（交给 LLM），随后用虚拟线程延迟逐字播报“补签/授勋”仪式台词（不过 LLM，便于甲方审核）。
 */
@Slf4j
@Component
public class CompleteTaskFunction implements ToolsGlobalRegistry.GlobalFunction {

    private static final String TOOL_NAME = "complete_task";

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            Thread.ofVirtual().name("happy-task-", 0).factory());

    @Resource
    private SessionManager sessionManager;
    @Resource
    private AgentStateService agentStateService;
    @Resource
    private HappyPlanetSpeaker happyPlanetSpeaker;

    ToolCallback toolCallback = FunctionToolCallback
            // 用 Map<String,Object>：模型可能把 taskId 作为 JSON 数字下发，避免对 Integer 值做 String 强转抛 CCE
            .builder(TOOL_NAME, (Map<String, Object> params, ToolContext toolContext) -> {
                String sessionId = (String) toolContext.getContext().get(Persona.TOOL_CONTEXT_SESSION_ID_KEY);
                ChatSession chatSession = sessionManager.getSession(sessionId);
                if (chatSession == null || chatSession.getDevice() == null) {
                    return "任务完成没记上，等下再试试。";
                }
                DeviceBO device = chatSession.getDevice();
                Long taskId = parseTaskId(params.get("taskId"));
                try {
                    AgentStateService.TaskDoneResult result =
                            agentStateService.markTaskDone(device.getDeviceId(), device.getRoleId(), taskId);
                    if (!result.marked()) {
                        return "现在好像没有正在进行的星球任务哦。";
                    }
                    boolean repaired = result.repaired();
                    boolean hasBadge = result.newBadges() != null && !result.newBadges().isEmpty();
                    if (repaired || hasBadge) {
                        scheduler.schedule(() -> {
                            try {
                                if (repaired) {
                                    happyPlanetSpeaker.speakOneOf(chatSession, "streak_repaired");
                                }
                                if (hasBadge) {
                                    happyPlanetSpeaker.speakOneOf(chatSession, "badge_awarded");
                                }
                            } catch (Exception ex) {
                                log.warn("播报任务完成仪式失败 - SessionId: {}", sessionId, ex);
                            }
                        }, 120, TimeUnit.MILLISECONDS);
                    }
                    return "太好了，今天的星球任务完成啦，快乐能量补充了一点点。";
                } catch (Exception e) {
                    log.error("完成星球任务异常，taskId={}", taskId, e);
                    return "任务完成没记上，等下再试试。";
                }
            })
            .toolMetadata(new XiaozhiToolMetadata(true))
            .description("当用户明确表示已经完成了今天的星球任务时调用，用于把今日任务标记为完成。"
                    + "通常不需要传 taskId（表示完成当前任务）。")
            .inputSchema("""
                    {
                        "type": "object",
                        "properties": {
                            "taskId": { "type": "string", "description": "可选，已完成的星球任务ID；一般留空表示完成当前任务" }
                        }
                    }
                """)
            .inputType(Map.class)
            .toolCallResultConverter(ToolCallStringResultConverter.INSTANCE)
            .build();

    private Long parseTaskId(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
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
        return "完成星球任务";
    }
}
