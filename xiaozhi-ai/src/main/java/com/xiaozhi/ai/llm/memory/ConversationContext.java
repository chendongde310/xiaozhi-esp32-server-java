package com.xiaozhi.ai.llm.memory;

/**
 * 运行时上下文，每次构建 Prompt 时传入。
 * 与 Conversation 的身份属性（ownerId/roleId/sessionId）不同，
 * 这些字段在会话期间可能变化（设备搬迁等）。
 *
 * <p>会话期内<b>稳定</b>的字段（如 location）随稳定的角色 System Prompt 一起注入，
 * 保持前缀 KV cache 有效；而是由 {@link UserMessageAssembler} 拼接到各条 UserMessage 的文本前缀。
 *
 * <p>{@code agentStateText} 是<b>每轮可变</b>的动态状态（快乐能量 / 陪伴天数 / 当前频道 / 档案摘要），
 * 作为一条独立的、位于稳定 System Prompt 之后的 SystemMessage 注入，避免污染稳定前缀。
 *
 * @param location       设备位置 / Web 端 IP 定位 / null（稳定）
 * @param agentStateText 每轮动态状态提示词 / null（不注入）
 */
public record ConversationContext(
    String location,
    String agentStateText
) {
    public static final ConversationContext EMPTY = new ConversationContext(null, null);

    /** 便利构造：仅位置，无动态状态。 */
    public ConversationContext(String location) {
        this(location, null);
    }
}
