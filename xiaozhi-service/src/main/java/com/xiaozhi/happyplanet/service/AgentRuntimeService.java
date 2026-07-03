package com.xiaozhi.happyplanet.service;

import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentBadgeDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.EnergyLogDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlanetTaskDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能体运行时编排：把状态/能量/任务/档案汇聚成注入 LLM 的"动态状态"提示词，
 * 并对外提供档案+状态的组合视图（供管理端）。
 *
 * <p>该服务位于 xiaozhi-service，无 Spring AI 依赖，可被 dialogue 与 server 两个进程复用。
 */
@Slf4j
@Service
public class AgentRuntimeService {

    /** Demo 阶段任务面向人群，默认全部。 */
    public static final String DEFAULT_AUDIENCE = "all";

    @Resource
    private AgentStateService agentStateService;
    @Resource
    private AgentProfileService agentProfileService;
    @Resource
    private PlanetTaskService planetTaskService;
    @Resource
    private EnergyService energyService;
    @Resource
    private BadgeService badgeService;
    @Resource
    private EnergyLogService energyLogService;
    @Resource
    private GrowthPromptService growthPromptService;

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

    /** 距上次聊天达到此天数即视为「久别重逢」，注入重逢语气槽位。 */
    private static final int REUNION_GAP_DAYS = 3;

    /**
     * 处理一轮用户输入并返回注入系统提示词的"动态状态"文本。
     * 会就地推进能量 / 陪伴天数 / 当日任务并持久化。
     *
     * @return 动态状态提示词；deviceId/roleId 缺失时返回 null（跳过注入）。
     */
    public String buildTurnState(String deviceId, Integer roleId, String userText, String emotion) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return null;
        }
        try {
            AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
            // 必须在 recordInteraction 之前捕获：它会把 lastInteractDate 改写为今天，随后无法据此判断久别。
            LocalDate prevInteractDate = state.getLastInteractDate();
            int delta = energyService.deltaForTurn(userText, emotion);
            agentStateService.recordInteraction(state, delta, DEFAULT_AUDIENCE);
            return renderStatePrompt(deviceId, roleId, state, prevInteractDate);
        } catch (Exception e) {
            log.warn("构建动态状态提示词失败 deviceId={}, roleId={}", deviceId, roleId, e);
            return null;
        }
    }

    /**
     * 本轮语音/表情情绪：驱动 TTS 声音情绪与设备表情。
     * 温暖的陪伴默认「happy」；用户明显负面、能量偏低、或处于夜间/能量修复频道时转为「neutral」（温柔、不镜像负面）。
     *
     * @param userEmotion STT 识别到的用户语气（可为 null）
     * @return 设备/内部情绪词（happy/neutral/…），null 表示不指定
     */
    public String currentTtsEmotion(String deviceId, Integer roleId, String userEmotion) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return null;
        }
        try {
            if (userEmotion != null && (userEmotion.equals("sad") || userEmotion.equals("angry")
                    || userEmotion.equals("crying") || userEmotion.equals("fear") || userEmotion.equals("hate"))) {
                return "neutral";
            }
            AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
            String channel = state.getCurrentChannel();
            if (AgentStateService.CHANNEL_NIGHT.equals(channel)
                    || AgentStateService.CHANNEL_ENERGY_REPAIR.equals(channel)) {
                return "neutral";
            }
            int energy = state.getEnergy() == null ? EnergyService.DEFAULT_ENERGY : state.getEnergy();
            return energyService.isLow(energy) ? "neutral" : "happy";
        } catch (Exception e) {
            log.warn("计算本轮情绪失败 deviceId={}, roleId={}", deviceId, roleId, e);
            return null;
        }
    }

    /** 只读地构建动态状态提示词（不推进能量），供非对话场景使用。 */
    public String peekStatePrompt(String deviceId, Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return null;
        }
        AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
        // 只读场景：状态未推进，lastInteractDate 即真实的上次互动日，可直接用于久别判断。
        return renderStatePrompt(deviceId, roleId, state, state.getLastInteractDate());
    }

    /**
     * 组装每轮注入 LLM 的「动态状态提示词」。文案全部来自可按角色编辑的成长提示词槽位
     * （{@link GrowthPromptSlot} 定义默认，{@link GrowthPromptService} 分层覆盖）；本方法只负责
     * 依据实时状态判定注入哪些槽位、并提供占位变量。
     *
     * @param prevInteractDate 本轮之前的上次互动日（用于久别重逢判断），可为 null（从未互动）。
     */
    private String renderStatePrompt(String deviceId, Integer roleId, AgentStateDO state, LocalDate prevInteractDate) {
        int energy = state.getEnergy() == null ? EnergyService.DEFAULT_ENERGY : state.getEnergy();
        int days = state.getCompanionDays() == null ? 0 : state.getCompanionDays();
        int streak = state.getStreakDays() == null ? 0 : state.getStreakDays();
        CompanionStage stage = CompanionStage.fromDays(days);
        String channel = state.getCurrentChannel();
        Map<String, String> profile = agentProfileService.map(deviceId, roleId);
        String taskText = currentTaskText(state);
        boolean taskDone = Integer.valueOf(1).equals(state.getCurrentTaskDone());

        long gapDays = prevInteractDate == null ? 0 : ChronoUnit.DAYS.between(prevInteractDate, LocalDate.now());
        boolean firstMeet = days <= 1 && profile.isEmpty();
        boolean reunion = !firstMeet && gapDays >= REUNION_GAP_DAYS;

        Map<String, String> vars = new HashMap<>();
        vars.put("energy", String.valueOf(energy));
        vars.put("energyMood", energyService.moodWord(energy));
        vars.put("days", String.valueOf(days));
        vars.put("stageLabel", stage.label());
        vars.put("streak", String.valueOf(streak));
        vars.put("gapDays", String.valueOf(gapDays));
        if (StringUtils.hasText(taskText)) {
            vars.put("taskText", taskText);
        }
        if (!profile.isEmpty()) {
            vars.put("profileText", describeProfile(profile));
        }

        GrowthPromptService.Renderer r = growthPromptService.renderer(roleId);
        List<String> parts = new ArrayList<>();

        addPart(parts, r.render(GrowthPromptSlot.STATE_HEADER, vars));

        GrowthPromptSlot energySlot = energyService.isLow(energy) ? GrowthPromptSlot.ENERGY_LOW
                : "high".equals(energyService.level(energy)) ? GrowthPromptSlot.ENERGY_HIGH
                : GrowthPromptSlot.ENERGY_MID;
        addPart(parts, r.render(energySlot, vars));

        addPart(parts, r.render(stageSlot(stage), vars));

        // 显式频道覆盖时间段默认；无显式频道则按服务器时间选时间段语气。
        GrowthPromptSlot ambientSlot = channelSlot(channel);
        if (ambientSlot == null) {
            ambientSlot = timeSlot(LocalTime.now());
        }
        addPart(parts, r.render(ambientSlot, vars));

        if (firstMeet) {
            addPart(parts, r.render(GrowthPromptSlot.EVENT_FIRST_MEET, vars));
        } else if (reunion) {
            addPart(parts, r.render(GrowthPromptSlot.EVENT_REUNION, vars));
        }

        if (streak >= 2) {
            addPart(parts, r.render(GrowthPromptSlot.HINT_STREAK, vars));
        }

        if (StringUtils.hasText(taskText) && !taskDone) {
            addPart(parts, r.render(GrowthPromptSlot.HINT_TASK, vars));
        }

        if (!profile.isEmpty()) {
            addPart(parts, r.render(GrowthPromptSlot.HINT_PROFILE, vars));
        }

        addPart(parts, r.render(GrowthPromptSlot.TTS_RULES, vars));

        return String.join("\n", parts);
    }

    private static void addPart(List<String> parts, String s) {
        if (StringUtils.hasText(s)) {
            parts.add(s);
        }
    }

    private static GrowthPromptSlot stageSlot(CompanionStage stage) {
        return switch (stage) {
            case ACQUAINTED -> GrowthPromptSlot.STAGE_ACQUAINTED;
            case FAMILIAR -> GrowthPromptSlot.STAGE_FAMILIAR;
            case OLD_FRIEND -> GrowthPromptSlot.STAGE_OLDFRIEND;
            case SOULMATE -> GrowthPromptSlot.STAGE_SOULMATE;
        };
    }

    /** 显式频道对应的槽位；日间/无频道返回 null（改用时间段语气）。 */
    private static GrowthPromptSlot channelSlot(String channel) {
        if (channel == null) {
            return null;
        }
        return switch (channel) {
            case AgentStateService.CHANNEL_NIGHT -> GrowthPromptSlot.CHANNEL_NIGHT;
            case AgentStateService.CHANNEL_CHILDHOOD -> GrowthPromptSlot.CHANNEL_CHILDHOOD;
            case AgentStateService.CHANNEL_ENERGY_REPAIR -> GrowthPromptSlot.CHANNEL_ENERGY_REPAIR;
            default -> null;
        };
    }

    /** 服务器本地时间映射到时间段槽位。 */
    private static GrowthPromptSlot timeSlot(LocalTime now) {
        int h = now.getHour();
        if (h >= 5 && h < 11) {
            return GrowthPromptSlot.TIME_MORNING;
        }
        if (h >= 11 && h < 18) {
            return GrowthPromptSlot.TIME_DAY;
        }
        if (h >= 18 && h < 22) {
            return GrowthPromptSlot.TIME_EVENING;
        }
        return GrowthPromptSlot.TIME_NIGHT;
    }

    public String currentTaskText(AgentStateDO state) {
        if (state == null || state.getCurrentTaskId() == null) {
            return null;
        }
        PlanetTaskDO task = planetTaskService.getById(state.getCurrentTaskId());
        return task == null ? null : task.getContent();
    }

    private String describeProfile(Map<String, String> profile) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : profile.entrySet()) {
            String label = AgentProfileService.PROFILE_LABELS.getOrDefault(e.getKey(), e.getKey());
            if (!first) {
                sb.append("；");
            }
            sb.append(label).append("是").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    public String channelLabel(String channel) {
        if (channel == null) {
            return "日间陪伴";
        }
        return switch (channel) {
            case AgentStateService.CHANNEL_NIGHT -> "夜间频道";
            case AgentStateService.CHANNEL_CHILDHOOD -> "童年频道";
            case AgentStateService.CHANNEL_ENERGY_REPAIR -> "能量修复频道";
            default -> "日间陪伴";
        };
    }

    /**
     * 组合视图：档案 + 运行状态 + 当前任务，供管理端"快乐星球档案"页展示。
     */
    public Map<String, Object> describe(String deviceId, Integer roleId) {
        AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("deviceId", deviceId);
        view.put("roleId", roleId);
        view.put("energy", state.getEnergy());
        view.put("energyLevel", energyService.level(state.getEnergy() == null ? 0 : state.getEnergy()));
        view.put("companionDays", state.getCompanionDays());
        view.put("currentChannel", state.getCurrentChannel());
        view.put("currentChannelLabel", channelLabel(state.getCurrentChannel()));
        view.put("firstConnected", state.getFirstConnected());
        view.put("profile", agentProfileService.map(deviceId, roleId));

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("content", currentTaskText(state));
        task.put("done", Integer.valueOf(1).equals(state.getCurrentTaskDone()));
        task.put("date", state.getLastTaskDate());
        view.put("todayTask", task);

        // —— 成长体系（P1+P2）——
        int days = state.getCompanionDays() == null ? 0 : state.getCompanionDays();
        int stageLevel = CompanionStage.fromDays(days).level();
        view.put("stage", stageView(days));
        view.put("streak", streakView(state));
        view.put("badges", badgeWall(deviceId, roleId));
        view.put("memoryStarMap", agentProfileService.starMap(deviceId, roleId, stageLevel));
        view.put("energyCurve", energyCurve(deviceId, roleId, 7));
        return view;
    }

    /** 羁绊阶段视图：等级 / 名称 / 键 / 阶段内进度 / 下一阶段。 */
    public Map<String, Object> stageView(int days) {
        CompanionStage s = CompanionStage.fromDays(days);
        CompanionStage next = s.next();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", s.level());
        m.put("name", s.label());
        m.put("key", s.key());
        m.put("progress", s.progressPercent(days));
        m.put("nextName", next == s ? null : next.label());
        m.put("nextDays", next == s ? null : next.minDays());
        return m;
    }

    private Map<String, Object> streakView(AgentStateDO state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", nz(state.getStreakDays()));
        m.put("best", nz(state.getBestStreak()));
        m.put("repairLeft", nz(state.getStreakRepairLeft()));
        return m;
    }

    /** 徽章墙：目录全集叠加“是否已获得 + 获得日期”，供前端渲染已点亮/未点亮。 */
    private List<Map<String, Object>> badgeWall(String deviceId, Integer roleId) {
        Map<String, LocalDate> earnedDates = new HashMap<>();
        for (AgentBadgeDO b : badgeService.list(deviceId, roleId)) {
            earnedDates.put(b.getBadgeKey(), b.getEarnDate());
        }
        List<Map<String, Object>> wall = new ArrayList<>();
        for (BadgeCatalog.Badge b : BadgeCatalog.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", b.key());
            m.put("label", b.label());
            m.put("desc", b.desc());
            m.put("icon", b.icon());
            m.put("category", b.category());
            boolean earned = earnedDates.containsKey(b.key());
            m.put("earned", earned);
            m.put("earnDate", earnedDates.get(b.key()));
            wall.add(m);
        }
        return wall;
    }

    /**
     * 近 {@code days} 天的能量曲线：每天取当天最后一次记录值；无记录的日子按前值向后填充，
     * 起点缺省用当前能量兜底，保证前端 sparkline 始终有 {@code days} 个点。
     */
    public List<Map<String, Object>> energyCurve(String deviceId, Integer roleId, int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        Map<LocalDate, Integer> lastOfDay = new HashMap<>();
        for (EnergyLogDO row : energyLogService.since(deviceId, roleId, from)) {
            if (row.getLogDate() != null && row.getEnergy() != null) {
                lastOfDay.put(row.getLogDate(), row.getEnergy()); // 升序遍历，后者覆盖=当天最后值
            }
        }
        int carry = EnergyService.DEFAULT_ENERGY;
        // 用最早一天的记录作为向后填充的起点；无任何记录时退回“当前能量”
        AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
        if (state.getEnergy() != null) {
            carry = state.getEnergy();
        }
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            if (lastOfDay.containsKey(d)) {
                carry = lastOfDay.get(d);
                break;
            }
        }
        List<Map<String, Object>> curve = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            if (lastOfDay.containsKey(d)) {
                carry = lastOfDay.get(d);
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", d.format(MD));
            point.put("energy", carry);
            curve.add(point);
        }
        return curve;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
