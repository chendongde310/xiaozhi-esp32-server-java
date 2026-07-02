package com.xiaozhi.dialogue.happyplanet;

import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.communication.common.SessionManager;
import com.xiaozhi.dialogue.llm.factory.PersonaFactory;
import com.xiaozhi.dialogue.runtime.Persona;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ProactiveConfigDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ScriptLineDO;
import com.xiaozhi.happyplanet.service.AgentStateService;
import com.xiaozhi.happyplanet.service.ProactiveConfigService;
import com.xiaozhi.happyplanet.service.ProactiveGuard;
import com.xiaozhi.happyplanet.service.ScriptLineService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 待命主动搭话决策与播报核心。
 *
 * <p>设备待命时自我唤醒并在 hello 中带 {@code wake_source=proactive}；本服务在音频通道就绪后，
 * 依据 {@link ProactiveConfigDO}（可控变量）与 {@link AgentStateDO} 运行时护栏计数，
 * 用纯逻辑 {@link ProactiveGuard} 判定是否可主动搭话——不通过则静默放弃，设备自然超时关闭。
 *
 * <p>内容遵循“脚本为主 + LLM 增强”：默认播报 sys_script_lines 里逐字可审的正向台词；
 * 仅在 allowLlm 且命中增强场景时，用带安全边界的 persona 生成一句更自然的开场。
 *
 * <p>护栏顺序（“主动 ≠ 打扰”）：开关 → 硬静默时段 → 活跃时段 → 当日退避 → 次数上限 → 冷却。
 * 主动播报后若在宽限期内无用户回应，标记“当日被忽略”，当天不再主动。
 */
@Slf4j
@Service
public class ProactiveChatService {

    public static final String WAKE_SOURCE_PROACTIVE = "proactive";

    /** 播报前的短暂延迟，等待 hello 握手与音频通道稳定（毫秒）。 */
    private static final long SPEAK_DELAY_MS = 1500L;
    /** 主动播报后多久检查是否被忽略（秒），需大于播报时长且在不活跃超时(60s)之内。 */
    private static final long IGNORE_CHECK_DELAY_SEC = 45L;
    /** 播报自身刷新 lastActivity 的宽限秒：超过此秒仍无活动即视为无人回应。 */
    private static final int SPEAK_GRACE_SEC = 15;

    /** 允许 LLM 增强开场的场景（其余一律走脚本台词）。 */
    private static final String SCENE_INVITE = "proactive_invite";
    private static final String SCENE_MORNING = "proactive_morning";
    private static final String SCENE_CARE = "proactive_care";
    private static final String SCENE_CHEER = "proactive_cheer";
    private static final String SCENE_TASK = "proactive_task";

    /** LLM 增强开场的约束提示词：主动、简短、有边界，绝不消极/监视感/索取关注。 */
    private static final String LLM_OPENING_INSTRUCTION =
            "（这是一次系统触发的主动搭话，用户此刻并没有对你说话。）"
            + "请你以温暖、有边界感的语气，主动、简短地跟对方打个招呼、开启一次轻聊，一句话即可。"
            + "要点：给对方“不想聊也没关系”的余地；不要追问、不要连发问题；"
            + "不要提到你在等待、在看着、在被冷落；不要卖惨或索取关注；不要说教或灌鸡汤。"
            + "就像一个惦记着对方的朋友，随口关心一句。";

    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            Thread.ofVirtual().name("proactive-chat-", 0).factory());

    @Resource
    private SessionManager sessionManager;
    @Resource
    private ProactiveConfigService proactiveConfigService;
    @Resource
    private AgentStateService agentStateService;
    @Resource
    private ScriptLineService scriptLineService;
    @Resource
    private HappyPlanetSpeaker happyPlanetSpeaker;
    // @Lazy 打破潜在的 Bean 循环依赖（PersonaFactory 构建链较长）。
    @Resource
    @Lazy
    private PersonaFactory personaFactory;

    /**
     * 设备自我唤醒后的主动开场入口。由 hello 处理在 wake_source=proactive 时调用（已在虚拟线程中延迟）。
     * 幂等安全：任一护栏不通过即静默返回。
     */
    public void maybeProactiveGreeting(ChatSession session) {
        scheduler.schedule(() -> {
            try {
                doGreeting(session);
            } catch (Exception e) {
                log.warn("主动搭话执行异常 - SessionId: {}", session != null ? session.getSessionId() : null, e);
            }
        }, SPEAK_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void doGreeting(ChatSession session) {
        if (!ready(session)) {
            return;
        }
        DeviceBO device = session.getDevice();
        String deviceId = device.getDeviceId();
        Integer roleId = device.getRoleId();

        ProactiveConfigDO cfg = proactiveConfigService.find(deviceId, roleId);
        if (cfg == null || !Integer.valueOf(1).equals(cfg.getEnabled())) {
            log.debug("主动搭话未开启，静默关闭自我唤醒连接 - SessionId: {}", session.getSessionId());
            closeQuietly(session);
            return;
        }
        proactiveConfigService.applyDefaults(cfg);

        AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        LocalTime nowTime = now.toLocalTime();

        boolean sameDay = today.equals(state.getProactiveDate());
        int todayCount = sameDay ? nz(state.getProactiveCount()) : 0;
        boolean ignoredToday = sameDay && Integer.valueOf(1).equals(state.getProactiveIgnoredToday());
        Long minutesSinceLast = null;
        if (state.getLastProactiveTime() != null) {
            long m = ChronoUnit.MINUTES.between(state.getLastProactiveTime(), now);
            minutesSinceLast = Math.max(0, m);
        }

        ProactiveGuard.Decision decision = ProactiveGuard.evaluate(
                true, nowTime,
                cfg.getActiveStart(), cfg.getActiveEnd(),
                cfg.getQuietStart(), cfg.getQuietEnd(),
                cfg.getDailyLimit(), todayCount, ignoredToday,
                cfg.getCooldownMinutes(), minutesSinceLast);

        if (decision != ProactiveGuard.Decision.OK) {
            log.info("主动搭话放弃({})，静默关闭 - SessionId: {}, deviceId: {}", decision, session.getSessionId(), deviceId);
            closeQuietly(session);
            return;
        }

        // 通过全部护栏：选择场景并播报
        String scene = chooseScene(nowTime.getHour(), state.getEnergy());
        boolean spoke = speak(session, scene, Integer.valueOf(1).equals(cfg.getAllowLlm()));
        if (!spoke) {
            log.warn("主动搭话未产生内容(场景 {} 无台词)，静默关闭 - SessionId: {}", scene, session.getSessionId());
            closeQuietly(session);
            return;
        }

        Instant greetingAt = Instant.now();
        agentStateService.recordProactive(deviceId, roleId, now);
        log.info("主动搭话已触发 - SessionId: {}, deviceId: {}, scene: {}, 当日第 {} 次",
                session.getSessionId(), deviceId, scene, todayCount + 1);

        scheduleIgnoreCheck(session, deviceId, roleId, greetingAt, today);
    }

    /** 依时段与能量选择场景。上午问候；能量偏低给鼓励；其余在邀请/关心/任务间轮换。 */
    private String chooseScene(int hour, Integer energy) {
        int e = energy == null ? 80 : energy;
        if (hour < 11) {
            return SCENE_MORNING;
        }
        if (e < 40) {
            return SCENE_CHEER;
        }
        switch (ThreadLocalRandom.current().nextInt(3)) {
            case 0:
                return SCENE_INVITE;
            case 1:
                return SCENE_CARE;
            default:
                return SCENE_TASK;
        }
    }

    /**
     * 播报开场：默认脚本台词；仅当 allowLlm 且场景为邀请类时用 LLM 增强。
     * @return 是否成功产生了开场内容
     */
    private boolean speak(ChatSession session, String scene, boolean allowLlm) {
        if (allowLlm && SCENE_INVITE.equals(scene)) {
            try {
                Persona persona = personaFactory.buildPersona(session);
                if (persona != null) {
                    persona.chat(LLM_OPENING_INSTRUCTION, false);
                    return true;
                }
            } catch (Exception e) {
                log.warn("LLM 增强开场失败，回退脚本台词 - scene: {}", scene, e);
            }
            // 回退脚本
        }
        ScriptLineDO line = scriptLineService.randomLine(scene);
        if (line == null) {
            return false;
        }
        happyPlanetSpeaker.speakLine(session, line.getContent(), line.getEmotion());
        return true;
    }

    /** 播报后延迟检查是否被忽略；无人回应则标记“当日退避”。 */
    private void scheduleIgnoreCheck(ChatSession session, String deviceId, Integer roleId,
                                     Instant greetingAt, LocalDate today) {
        scheduler.schedule(() -> {
            try {
                if (session.isAudioChannelOpen()
                        && ProactiveGuard.looksIgnored(greetingAt, session.getLastActivityTime(), SPEAK_GRACE_SEC)) {
                    agentStateService.markProactiveIgnored(deviceId, roleId, today);
                    log.info("主动搭话被忽略，当日退避 - deviceId: {}", deviceId);
                }
            } catch (Exception e) {
                log.debug("主动搭话忽略检查异常 - deviceId: {}", deviceId, e);
            }
        }, IGNORE_CHECK_DELAY_SEC, TimeUnit.SECONDS);
    }

    /**
     * 决定不主动搭话时，静默关闭本次自我唤醒建立的连接。
     * 否则会话会空转到不活跃超时(60s)，届时会播报“告别语”——在不该出声的时段(如设备时区偏差导致的夜间自我唤醒)造成打扰。
     */
    private void closeQuietly(ChatSession session) {
        try {
            sessionManager.closeSession(session);
        } catch (Exception e) {
            log.debug("静默关闭会话异常 - SessionId: {}", session.getSessionId(), e);
        }
    }

    private boolean ready(ChatSession session) {
        if (session == null || !session.isAudioChannelOpen()) {
            return false;
        }
        DeviceBO device = session.getDevice();
        return device != null && device.getRoleId() != null;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
