package com.xiaozhi.server.web.chat;

import com.xiaozhi.ai.llm.factory.ChatModelFactory;
import com.xiaozhi.ai.llm.memory.ChatMemory;
import com.xiaozhi.ai.llm.memory.Conversation;
import com.xiaozhi.ai.llm.memory.ConversationContext;
import com.xiaozhi.ai.llm.memory.MessageTimeMetadata;
import com.xiaozhi.ai.llm.memory.MessageWindowConversation;
import com.xiaozhi.common.model.ChatToken;
import com.xiaozhi.common.model.bo.MessageBO;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.happyplanet.service.AgentRuntimeService;
import com.xiaozhi.message.service.MessageService;
import com.xiaozhi.role.service.RoleService;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

import lombok.extern.slf4j.Slf4j;
/**
 * Web 聊天服务：为纯文本 Web 客户端提供流式 AI 对话能力。
 * 轻量级实现，不涉及 STT/TTS/Player 等音频组件
 */
@Slf4j
@Service
public class WebChatService {
    @Resource
    private ChatModelFactory chatModelFactory;
    @Resource
    private RoleService roleService;
    @Resource
    private ChatMemory chatMemory;
    @Resource
    private MessageService messageService;
    @Resource
    private AgentRuntimeService agentRuntimeService;

    @Value("${conversation.max-messages:16}")
    private int maxMessages;

    @Value("${conversation.app-max-messages:8}")
    private int appMaxMessages;

    @Value("${conversation.app-first-content-timeout-seconds:6}")
    private long appFirstContentTimeoutSeconds;

    /**
     * sessionId → Conversation 映射
     */
    private final ConcurrentHashMap<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChatModel> chatModels = new ConcurrentHashMap<>();

    /**
     * 开启一个 Web 聊天会话。
     * 当 {@code resumeSessionId} 为空时创建新会话；非空时尝试续接已有会话。
     * 续接时会校验归属（userId一致 且 source='web'），防止误用设备会话或跨用户访问。
     *
     * @param userId           当前登录用户ID
     * @param roleId           角色ID
     * @param resumeSessionId  续接的会话 ID，可为 null
     * @return sessionId
     */
    public String openSession(Integer userId, Integer roleId, String resumeSessionId) {
        return openSession(userId, roleId, "web:" + userId, resumeSessionId);
    }

    private ConversationContext conversationContext(Conversation conversation, String userText) {
        String ownerId = conversation.getOwnerId();
        if (!StringUtils.hasText(ownerId) || ownerId.startsWith("web:")) {
            return ConversationContext.EMPTY;
        }
        String agentStateText = agentRuntimeService.buildTurnState(
                ownerId,
                conversation.getRoleId(),
                userText,
                null
        );
        return new ConversationContext(null, agentStateText);
    }

    /**
     * 开启指定 ownerId 的文本聊天会话。
     * 用户端 APP 使用真实 deviceId 作为 ownerId，以便复用该 AI 的快乐星球状态与档案。
     */
    public String openSession(Integer userId, Integer roleId, String ownerId, String resumeSessionId) {
        return openSession(userId, roleId, ownerId, resumeSessionId, true);
    }

    public String openSession(Integer userId, Integer roleId, String ownerId, String resumeSessionId, boolean sessionScoped) {
        return openSession(userId, roleId, ownerId, resumeSessionId, sessionScoped, maxMessages);
    }

    public String openAppSession(Integer userId, Integer roleId, String ownerId, String resumeSessionId) {
        return openSession(userId, roleId, ownerId, resumeSessionId, false, appMaxMessages);
    }

    private String openSession(Integer userId, Integer roleId, String ownerId, String resumeSessionId,
                               boolean sessionScoped, int historyLimit) {
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("ownerId 不能为空");
        }

        RoleBO role = roleService.getBO(roleId);
        if (role == null) {
            throw new IllegalArgumentException("角色不存在: " + roleId);
        }

        String sessionId;
        if (StringUtils.hasText(resumeSessionId)) {
            assertSessionOwnedByUser(resumeSessionId, userId);
            sessionId = resumeSessionId;
        } else {
            sessionId = UUID.randomUUID().toString();
        }

        // 初始化 Conversation：Web 场景始终按 sessionId 加载（新会话为空，续接会拉到历史）。
        Conversation conversation = MessageWindowConversation.builder()
                .chatMemory(chatMemory)
                .maxMessages(historyLimit)
                .ownerId(ownerId)
                .roleId(role.getRoleId())
                .roleDesc(role.getRoleDesc())
                .userId(userId)
                .sessionId(sessionId)
                .sessionScoped(sessionScoped)
                .build();
        conversations.put(sessionId, conversation);

        // 初始化 ChatModel
        ChatModel chatModel = chatModelFactory.getChatModel(role);
        chatModels.put(sessionId, chatModel);

        log.info("Web 聊天会话已创建: sessionId={}, userId={}, ownerId={}, roleId={}, resume={}, historyLimit={}",
                sessionId, userId, ownerId, roleId, StringUtils.hasText(resumeSessionId), historyLimit);
        return sessionId;
    }

    /**
     * 创建新会话的便捷重载。
     */
    public String openSession(Integer userId, Integer roleId) {
        return openSession(userId, roleId, null);
    }

    /**
     * 校验待续接的 sessionId 归属于当前用户的 Web 会话。
     * 存在不匹配时抛出 IllegalArgumentException。
     */
    private void assertSessionOwnedByUser(String sessionId, Integer userId) {
        List<MessageBO> recent = messageService.listHistory(sessionId, 1);
        if (recent.isEmpty()) {
            throw new IllegalArgumentException("会话不存在或已清除: " + sessionId);
        }
        MessageBO first = recent.get(0);
        if (!MessageBO.SOURCE_WEB.equals(first.getSource())) {
            throw new IllegalArgumentException("仅支持续接 Web 来源的会话: " + sessionId);
        }
        if (!userId.equals(first.getUserId())) {
            throw new IllegalArgumentException("会话不属于当前用户: " + sessionId);
        }
    }

    /**
     * 流式聊天：接收用户文本，返回 AI 回复的 ChatToken 流（包含思考过程和正式回复），
     * 并在完成时持久化 user/assistant 两条消息。
     *
     * @param sessionId 会话 ID
     * @param text      用户输入文本
     * @return ChatToken 流，前端可根据 type 区分 thinking/content
     */
    public Flux<ChatToken> chatStream(String sessionId, String text) {
        Conversation conversation = conversations.get(sessionId);
        ChatModel chatModel = chatModels.get(sessionId);
        if (conversation == null || chatModel == null) {
            return Flux.error(new IllegalStateException("会话不存在或已过期: " + sessionId));
        }

        // Web 场景：裸文本 UserMessage + 时间戳 metadata；
        // Conversation 投影层会在送 LLM 前拼出 [时间戳] 文本 的前缀。
        // 无 speaker/emotion，故不挂 MessageMetadataBO。
        LocalDateTime userCreatedAt = LocalDateTime.now();
        Instant userInstant = userCreatedAt.atZone(ZoneId.systemDefault()).toInstant();
        UserMessage userMessage = new UserMessage(text);
        MessageTimeMetadata.setTimeMillis(userMessage, userInstant);
        conversation.add(userMessage);

        long requestStartNanos = System.nanoTime();

        // Web 场景无位置
        List<Message> messages = conversation.messages(conversationContext(conversation, text));

        Prompt prompt = new Prompt(messages);

        StringBuilder fullResponse = new StringBuilder();
        AtomicBoolean firstTokenLogged = new AtomicBoolean(false);
        AtomicBoolean emittedContent = new AtomicBoolean(false);
        boolean appConversation = isAppConversation(conversation);

        Flux<ChatToken> rawModelTokens = chatModel.stream(prompt)
                .mapNotNull(ChatResponse::getResult)
                .mapNotNull(Generation::getOutput)
                .flatMap(message -> {
                    List<ChatToken> tokens = new ArrayList<>();
                    Object reasoning = message.getMetadata().get("reasoningContent");
                    if (!appConversation && reasoning instanceof String r && !r.isEmpty()) {
                        tokens.add(ChatToken.thinking(r));
                    }
                    String content = message.getText();
                    if (content != null && !content.isEmpty()) {
                        tokens.add(ChatToken.content(content));
                    }
                    return Flux.fromIterable(tokens);
                });

        if (appConversation) {
            Duration firstContentTimeout = Duration.ofSeconds(Math.max(1, appFirstContentTimeoutSeconds));
            rawModelTokens = rawModelTokens
                    .doOnNext(token -> {
                        if (token.isContent()) {
                            emittedContent.set(true);
                        }
                    })
                    .takeUntilOther(Mono.delay(firstContentTimeout)
                            .filter(ignored -> !emittedContent.get())
                            .doOnNext(ignored -> log.warn(
                                    "App 聊天 {} 秒内未收到正式内容，停止思考流并使用兜底回复: sessionId={}, text={}",
                                    firstContentTimeout.toSeconds(), sessionId, abbreviate(text, 80))));
        }

        Flux<ChatToken> modelTokens = rawModelTokens
                .concatWith(Flux.defer(() -> {
                    if (emittedContent.get()) {
                        return Flux.empty();
                    }
                    String fallback = fallbackReply(text);
                    log.warn("模型流结束但未返回正式内容，使用 App 兜底回复: sessionId={}, text={}",
                            sessionId, abbreviate(text, 80));
                    return Flux.just(ChatToken.content(fallback));
                }))
                .doOnNext(token -> {
                    if (firstTokenLogged.compareAndSet(false, true)) {
                        log.info("Web 聊天首个模型 token: sessionId={}, cost={}ms, type={}",
                                sessionId, elapsedMillis(requestStartNanos), token.type());
                    }
                    // 只累积正式回复内容，思考过程不持久化
                    if (token.isContent()) {
                        emittedContent.set(true);
                        fullResponse.append(token.text());
                    }
                })
                .doOnComplete(() -> {
                    if (fullResponse.isEmpty()) {
                        return;
                    }
                    String reply = fullResponse.toString();
                    conversation.add(new AssistantMessage(reply));
                    // 持久化裸文本（元数据由 Conversation 投影层按需拼前缀，DB 保持干净）
                    persistTurn(conversation, text, userCreatedAt, reply, LocalDateTime.now());
                })
                .timeout(Duration.ofSeconds(45))
                .doOnError(e -> log.error("Web 聊天流式响应失败: sessionId={}", sessionId, e));

        return Flux.concat(
                Flux.just(ChatToken.status("星球记忆已准备，正在生成回复...")),
                modelTokens
        );
    }

    private long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private boolean isAppConversation(Conversation conversation) {
        String ownerId = conversation.getOwnerId();
        return StringUtils.hasText(ownerId) && !ownerId.startsWith("web:");
    }

    private String fallbackReply(String text) {
        String normalized = text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
        if (normalized.contains("聊") || normalized.contains("陪我")) {
            return "可以，我在。我们先不用聊很大的事，就从现在开始：你今天最想吐槽的，或者最想留住的一件小事是什么？";
        }
        if (normalized.contains("心情") || normalized.contains("难受") || normalized.contains("烦")) {
            return "可以，我们慢慢整理。你先不用说得很完整，只要告诉我：现在这份心情更像累、烦、委屈，还是说不清楚？";
        }
        if (normalized.contains("任务")) {
            return "好，给你一个很小的快乐任务：找一件今天还算顺眼的小事，把它用一句话告诉我。";
        }
        if (normalized.contains("星球码") || normalized.contains("绑定app") || normalized.contains("关联app")) {
            return "这件事需要设备端生成星球码。你可以直接对设备说“绑定 APP”，我会把星球码读给你。";
        }
        return "我在。刚才模型没有返回有效内容，我们换个更直接的方式聊：你想先说发生了什么，还是只想让我陪你安静一会儿？";
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 将一轮 Web 对话的 user + assistant 两条消息写入数据库（source='web'）。
     * 单独提出方便出错时不影响流式完成。
     */
    private void persistTurn(Conversation conversation, String userText, LocalDateTime userCreatedAt,
                             String assistantText, LocalDateTime assistantCreatedAt) {
        try {
            MessageBO userBO = buildMessageBO(conversation, MessageBO.SENDER_USER, userText, userCreatedAt);
            MessageBO assistantBO = buildMessageBO(conversation, MessageBO.SENDER_ASSISTANT, assistantText, assistantCreatedAt);
            messageService.saveAll(List.of(userBO, assistantBO));
        } catch (Exception e) {
            log.error("Web 聊天消息持久化失败: sessionId={}", conversation.sessionId(), e);
        }
    }

    private MessageBO buildMessageBO(Conversation conversation, String sender, String content, LocalDateTime createTime) {
        MessageBO bo = new MessageBO();
        bo.setUserId(conversation.getUserId());
        bo.setDeviceId(conversation.getOwnerId());
        bo.setSessionId(conversation.sessionId());
        bo.setSource(MessageBO.SOURCE_WEB);
        bo.setSender(sender);
        bo.setMessage(content);
        bo.setRoleId(conversation.getRoleId());
        bo.setMessageType(MessageBO.MESSAGE_TYPE_NORMAL);
        bo.setCreateTime(createTime);
        return bo;
    }

    /**
     * 关闭 Web 聊天会话，释放资源
     */
    public void closeSession(String sessionId) {
        conversations.remove(sessionId);
        chatModels.remove(sessionId);
        log.info("Web 聊天会话已关闭: sessionId={}", sessionId);
    }

    /**
     * 检查会话是否存在
     */
    public boolean hasSession(String sessionId) {
        return conversations.containsKey(sessionId);
    }

    public boolean isActiveSessionOwnedByUser(String sessionId, Integer userId) {
        if (!StringUtils.hasText(sessionId) || userId == null) {
            return false;
        }
        Conversation conversation = conversations.get(sessionId);
        return conversation != null && userId.equals(conversation.getUserId());
    }
}
