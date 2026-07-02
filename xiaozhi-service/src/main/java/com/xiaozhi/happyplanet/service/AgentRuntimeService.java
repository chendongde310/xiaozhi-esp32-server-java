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
import java.time.format.DateTimeFormatter;
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

    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");

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
            int delta = energyService.deltaForTurn(userText, emotion);
            agentStateService.recordInteraction(state, delta, DEFAULT_AUDIENCE);
            return renderStatePrompt(deviceId, roleId, state);
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
        return renderStatePrompt(deviceId, roleId, state);
    }

    private String renderStatePrompt(String deviceId, Integer roleId, AgentStateDO state) {
        int energy = state.getEnergy() == null ? EnergyService.DEFAULT_ENERGY : state.getEnergy();
        StringBuilder sb = new StringBuilder();
        sb.append("【当前陪伴状态】（仅供你把握语气与内容，不要机械复述这些数据）\n");
        sb.append("- 快乐能量：").append(energy).append("/100（").append(energyService.moodWord(energy)).append("）。");
        if (energyService.isLow(energy)) {
            sb.append("用户此刻可能有点累或情绪偏低，多一点耐心和安静的陪伴，别急着让对方开心，先陪着。");
        } else if ("high".equals(energyService.level(energy))) {
            sb.append("氛围不错，可以更轻松一点，适度分享一点小快乐。");
        }
        sb.append('\n');

        int days = state.getCompanionDays() == null ? 0 : state.getCompanionDays();
        if (days > 0) {
            sb.append("- 你们已经相伴第 ").append(days).append(" 天。\n");
        }

        CompanionStage stage = CompanionStage.fromDays(days);
        sb.append("- 你们的关系阶段：").append(stage.label()).append("。").append(stageTone(stage)).append('\n');

        int streak = state.getStreakDays() == null ? 0 : state.getStreakDays();
        if (streak >= 2) {
            sb.append("- TA 已连续完成 ").append(streak)
                    .append(" 天星球任务，可以在合适时机真诚地肯定这份坚持，但不必每轮都提、更不要施压。\n");
        }

        sb.append("- 当前频道：").append(channelLabel(state.getCurrentChannel()))
                .append("。").append(channelTone(state.getCurrentChannel())).append('\n');

        String taskText = currentTaskText(state);
        if (StringUtils.hasText(taskText)) {
            boolean done = Integer.valueOf(1).equals(state.getCurrentTaskDone());
            sb.append("- 今日星球任务：").append(taskText).append("（")
                    .append(done ? "已完成" : "未完成").append("）。");
            if (!done) {
                sb.append("如果时机合适可以轻轻地邀请对方完成，但对方没兴趣就不要勉强，更不要反复催促。");
            }
            sb.append('\n');
        }

        Map<String, String> profile = agentProfileService.map(deviceId, roleId);
        if (!profile.isEmpty()) {
            sb.append("- 你已经记住的TA：").append(describeProfile(profile))
                    .append("。请自然地体现这些了解，不要生硬罗列。\n");
        }
        sb.append("\n【说话语气·重要】你的回复会用带情感的声音合成播放，务必遵守：\n")
                .append("1) 绝对不要写任何括号旁白/动作/神态/心理描写，例如（轻声）（哽咽）（微笑）【难过地】[小声] 等——")
                .append("这些会被原样朗读出来、非常出戏。情绪只能通过“措辞和语气词”来表达：难过就“呜呜……”、")
                .append("开心就“哈哈”、惊讶就“哇”、生气就语气重一点，拖长情绪用“……”。\n")
                .append("2) 可在回复最开头加一个情绪标记 <e:xxx>（xxx 从 happy/sad/angry/surprised/excited/neutral 中选），")
                .append("依据你要说的内容与用户是否要求了语气来选（“带哭腔/难过点”→sad，“开心点”→happy，")
                .append("“温柔点/安静点/别激动”→neutral，“惊喜一下”→surprised）。该标记会被系统删除，不会朗读也不显示。\n");
        return sb.toString();
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

    private String channelTone(String channel) {
        if (channel == null) {
            return "";
        }
        return switch (channel) {
            case AgentStateService.CHANNEL_NIGHT ->
                    "语气更轻更慢，句子更短，不讲大道理、不做复杂任务，适合睡前安静陪伴。";
            case AgentStateService.CHANNEL_CHILDHOOD ->
                    "带一点怀旧和温柔，可以说“换一种方式继续陪你”，但绝不扮演任何原剧角色、不复述原剧台词或剧情。";
            case AgentStateService.CHANNEL_ENERGY_REPAIR ->
                    "把节奏放慢，先共情再陪伴，给对方“安静一会儿”或“聊两句”的选择权。";
            default -> "";
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

    private String stageTone(CompanionStage stage) {
        return switch (stage) {
            case ACQUAINTED -> "还在相互熟悉，语气礼貌温暖、不越界，多倾听。";
            case FAMILIAR -> "已经有点熟了，可以更自在、带点小玩笑，但仍不黏人。";
            case OLD_FRIEND -> "是老朋友了，有些默契不用从头解释，可自然地关心近况。";
            case SOULMATE -> "是最亲近的星球密友，可温柔而笃定地陪伴，但绝不诱导依赖、不说“只有我懂你”。";
        };
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
