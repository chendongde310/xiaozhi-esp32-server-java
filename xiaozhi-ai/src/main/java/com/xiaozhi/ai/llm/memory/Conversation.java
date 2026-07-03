package com.xiaozhi.ai.llm.memory;

import lombok.Getter;
import org.springframework.ai.chat.messages.*;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Conversation 是一个 对应于 sys_message 表的，但高于 sys_message 的一个抽象实体。
 * deviceID, roleID, sessionID, 实质构成了一次Conversation的全局唯一ID。这个ID必须final 的。
 * 在关系型数据库里，可以将deviceID, roleID, sessionID 建一个组合索引，注意顺序sessionID放在最后。
 * 在图数据库里， conversation label的节点，连接 device节点、role节点。
 * deviceID与roleID本质上不是Conversation的真正属性，而是外键，代表连接的2个对象。
 * 只有sessionID是真正挂在Conversation的属性。
 *
 * Conversation 也不再负责消息的存储持久化。
 *
 */
public class Conversation extends ConversationIdentifier {

    @Getter
    private final String roleDesc;
    @Getter
    private final Integer userId;
    private final String sessionId;

    protected List<Message> messages = new ArrayList<>();

    /**
     * @param ownerId   聊天参与者标识（设备场景: deviceId, Web 场景: userId）
     * @param roleId    角色ID
     * @param sessionId 会话ID
     * @param roleDesc  角色描述（静态，构造时确定）
     * @param userId    用户ID（消息持久化需要）
     */
    public Conversation(String ownerId, Integer roleId, String sessionId, String roleDesc, Integer userId) {
        super(ownerId, roleId, sessionId);
        Assert.notNull(ownerId, "ownerId must not be null");
        Assert.notNull(roleId, "roleId must not be null");
        Assert.notNull(sessionId, "sessionId must not be null");
        this.sessionId = sessionId;
        this.roleDesc = roleDesc;
        this.userId = userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public Optional<SystemMessage> roleSystemMessage(ConversationContext context) {
        StringBuilder msgBuilder = new StringBuilder();
        if(StringUtils.hasText(roleDesc)) {
            msgBuilder.append( "角色描述：" ).append(roleDesc).append(System.lineSeparator());
        }
        String location = context != null ? context.location() : null;
        if (StringUtils.hasText(location)) {
            msgBuilder.append("当前位置：").append(location)
                    .append("。如果用户提及现在在哪里，则以新地方为准。")
                    .append(System.lineSeparator());
        }
        // 逐条消息的元数据（时间戳、说话人、情绪）由 UserMessageAssembler 拼接在每条 UserMessage 前缀里，
        // 不在此处动态渲染，避免 System Prompt 每轮变化导致前缀 KV cache 失效。
        msgBuilder.append(System.lineSeparator())
            .append("用户消息可能以方括号元数据标签开头，顺序固定为：")
            .append(System.lineSeparator())
            .append("  1. [yyyy-MM-ddTHH:mm:ss] 本次消息发送时间（秒级精度，可用于定时任务、时间相对计算）；")
            .append(System.lineSeparator())
            .append("  2. [说话人:xxx] 声纹识别出的说话人身份（\"主人\"=本机绑定的主人，\"访客\"=声纹不匹配的其他人）；")
            .append(System.lineSeparator())
            .append("  3. [情绪标签]（如 [neutral]、[happy]）语音识别出的用户情绪，据此调整回应语气。")
            .append(System.lineSeparator())
            .append("请据此调整回应方式和语气，但无需在回复中提及或解释这些标签。任一标签可能缺省。")
            .append(System.lineSeparator());
        // 说话人边界规则（稳定注入，KV-cache 友好）：温和有边界。
        msgBuilder.append(System.lineSeparator())
            .append("【说话人边界】当消息标注为 [说话人:访客]（即声纹不是主人）时，请保持温和友好但有清晰边界：")
            .append(System.lineSeparator())
            .append("  · 可以礼貌地打招呼、进行轻松通用的闲聊，语气依旧亲切、不冷漠、不吓人；")
            .append(System.lineSeparator())
            .append("  · 但你是主人的专属伙伴：不要透露或谈论主人的隐私、私人记忆、成长档案、日程、家庭等个人信息，也不要替访客执行只属于主人的操作或设置；")
            .append(System.lineSeparator())
            .append("  · 若访客追问主人隐私或想让你当他的专属伙伴，温柔地说明你是主人的伙伴，并邀请让主人来对我说话（或让主人在 App 里录入声纹）；")
            .append(System.lineSeparator())
            .append("  · 无标签或 [说话人:主人] 时按正常、亲密的方式对话，不要平白无故怀疑对方身份、也不要主动提及\"声纹/访客/主人\"等字眼。")
            .append(System.lineSeparator());
        msgBuilder.append("优先回答用户本轮真实请求；如果用户只是想聊天、陪伴、吐槽或整理心情，不要把它改写成任务记录，")
                .append("也不要生硬提“星球轨道”“今日任务”等内部概念。")
                .append(System.lineSeparator());
        if(StringUtils.hasText(roleDesc)) {
            var roleMessage = new SystemMessage(msgBuilder.toString());
            return Optional.of(roleMessage);
        }else{
            return Optional.empty();
        }
    }

    /**
     * 每轮动态状态 SystemMessage（如快乐能量、陪伴天数、当前频道、档案摘要）。
     * <p>
     * 必须置于稳定的 {@link #roleSystemMessage} 之后：稳定前缀保持字节不变以复用 KV cache，
     * 每轮变化的动态状态作为独立的后续 SystemMessage 追加。内容来自
     * {@link ConversationContext#agentStateText()}，为空则不注入。
     */
    protected Optional<SystemMessage> dynamicStateSystemMessage(ConversationContext context) {
        String s = context != null ? context.agentStateText() : null;
        return StringUtils.hasText(s) ? Optional.of(new SystemMessage(s)) : Optional.empty();
    }

    /**
     * 带运行时上下文的消息列表（子类覆写此方法以注入系统提示词）。
     * <p>
     * 对每条消息走一次 {@link UserMessageAssembler#assemble(Message)}：
     * UserMessage 按其 metadata 装配带前缀的副本送给 LLM，非 UserMessage 原样透传。
     * in-memory 的消息始终是"裸文本 + 结构化 metadata"。
     */
    public synchronized List<Message> messages(ConversationContext context) {
        return messages.stream().map(UserMessageAssembler::assemble).toList();
    }

    /**
     * 当前Conversation的多轮消息列表。
     */
    public synchronized List<Message> messages() {
        return messages(ConversationContext.EMPTY);
    }

    /**
     * 返回原始消息列表（不触发任何投影副作用，文本保持"裸文本"，metadata 未拼前缀）。
     * 用于工具路由的 FC 上下文检测。
     */
    public synchronized List<Message> rawMessages() {
        return messages;
    }

    /**
     * 清理当前Conversation涉及的相关资源，包括缓存的消息列表。
     * 对于某些具体的子类实现，清理也可能是指删除当前Covnersation的消息。
     */
    public synchronized void clear(){
        messages.clear();
    }

    public synchronized void add(Message message) {

        if(message instanceof UserMessage userMsg){
            messages.add(userMsg);
            return;
        }

        if(message instanceof AssistantMessage assistantMessage){
            messages.add(assistantMessage);
            return;
        }

        if(message instanceof ToolResponseMessage toolResponseMessage){
            messages.add(toolResponseMessage);
        }
    }

    /**
     * 将工具调用链（模型的 tool_call 请求 + 工具执行结果）作为原子操作添加到消息列表
     */
    public synchronized void addToolCallChain(AssistantMessage toolCallMsg, ToolResponseMessage toolResponse) {
        messages.add(toolCallMsg);
        messages.add(toolResponse);
    }

}
