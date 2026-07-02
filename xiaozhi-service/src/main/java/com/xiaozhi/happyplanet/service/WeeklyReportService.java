package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentBadgeDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentProfileDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.EnergyLogDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ScriptLineDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.WeeklyReportDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.WeeklyReportMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 星球周报：把一周的能量曲线、任务完成、连击、新记住的事、新徽章汇聚成一份成长报告。
 *
 * <p>两种用法：<br>
 * · 滚动 7 天预览（{@link #rolling}）——实时计算不落库，供 App 即时查看与生成分享图；<br>
 * · 自然周归档（{@link #generateForLastWeek}）——每周定时生成并写入 {@code sys_weekly_report}，
 *   幂等（按 deviceId+roleId+periodStart 唯一），供历史回看与情绪数据资产沉淀。
 */
@Slf4j
@Service
public class WeeklyReportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM-dd");
    private static final int MAX_NEW_MEMORIES = 6;

    @Resource
    private AgentStateService agentStateService;
    @Resource
    private EnergyLogService energyLogService;
    @Resource
    private BadgeService badgeService;
    @Resource
    private AgentProfileService agentProfileService;
    @Resource
    private ScriptLineService scriptLineService;
    @Resource
    private WeeklyReportMapper weeklyReportMapper;

    // ---------------- 对外：滚动预览 / 归档 / 历史 ----------------

    /** 滚动 7 天报告（截至今天），实时计算不落库。 */
    public Map<String, Object> rolling(String deviceId, Integer roleId) {
        LocalDate end = LocalDate.now();
        return toApiMap(compute(deviceId, roleId, end.minusDays(6), end));
    }

    /** 生成并归档“最近一个完整自然周（周一~周日）”的周报，幂等。 */
    public WeeklyReportDO generateForLastWeek(String deviceId, Integer roleId) {
        LocalDate end = mostRecentSunday(LocalDate.now());
        return generateAndSave(deviceId, roleId, end.minusDays(6), end);
    }

    /** 生成指定周期的周报并落库（若已存在直接返回既有记录）。 */
    public WeeklyReportDO generateAndSave(String deviceId, Integer roleId, LocalDate start, LocalDate end) {
        WeeklyReportDO existing = find(deviceId, roleId, start);
        if (existing != null) {
            return existing;
        }
        WeeklyReportDO row = toDO(deviceId, roleId, compute(deviceId, roleId, start, end));
        try {
            weeklyReportMapper.insert(row);
            return row;
        } catch (DuplicateKeyException e) {
            return find(deviceId, roleId, start);
        }
    }

    /** 历史周报列表（最新在前），归档记录转为 API 视图。 */
    public List<Map<String, Object>> history(String deviceId, Integer roleId, int limit) {
        List<WeeklyReportDO> rows = weeklyReportMapper.selectList(new LambdaQueryWrapper<WeeklyReportDO>()
                .eq(WeeklyReportDO::getDeviceId, deviceId)
                .eq(WeeklyReportDO::getRoleId, roleId)
                .orderByDesc(WeeklyReportDO::getPeriodStart)
                .last("limit " + Math.max(1, Math.min(limit, 52))));
        List<Map<String, Object>> list = new ArrayList<>();
        for (WeeklyReportDO r : rows) {
            list.add(fromDO(r));
        }
        return list;
    }

    public WeeklyReportDO find(String deviceId, Integer roleId, LocalDate periodStart) {
        return weeklyReportMapper.selectOne(new LambdaQueryWrapper<WeeklyReportDO>()
                .eq(WeeklyReportDO::getDeviceId, deviceId)
                .eq(WeeklyReportDO::getRoleId, roleId)
                .eq(WeeklyReportDO::getPeriodStart, periodStart)
                .last("limit 1"));
    }

    // ---------------- 计算核心 ----------------

    /** 计算周期 [start, end] 的报告（内部结构）。 */
    private Report compute(String deviceId, Integer roleId, LocalDate start, LocalDate end) {
        AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
        int currentEnergy = state.getEnergy() == null ? EnergyService.DEFAULT_ENERGY : state.getEnergy();

        // 能量事件 → 每日最后值 → 逐日曲线（缺口按前值填充）
        Map<LocalDate, Integer> lastOfDay = new HashMap<>();
        for (EnergyLogDO r : energyLogService.since(deviceId, roleId, start)) {
            if (r.getLogDate() != null && !r.getLogDate().isAfter(end) && r.getEnergy() != null) {
                lastOfDay.put(r.getLogDate(), r.getEnergy());
            }
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1L;
        int dayCount = (int) Math.max(1, span);
        int carry = currentEnergy;
        for (int i = 0; i < dayCount; i++) {
            LocalDate d = start.plusDays(i);
            if (lastOfDay.containsKey(d)) {
                carry = lastOfDay.get(d);
                break;
            }
        }
        List<Integer> values = new ArrayList<>();
        List<Map<String, Object>> curve = new ArrayList<>();
        for (int i = 0; i < dayCount; i++) {
            LocalDate d = start.plusDays(i);
            if (lastOfDay.containsKey(d)) {
                carry = lastOfDay.get(d);
            }
            values.add(carry);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", d.format(MD));
            p.put("energy", carry);
            curve.add(p);
        }
        int min = values.get(0);
        int max = values.get(0);
        int sum = 0;
        for (int v : values) {
            min = Math.min(min, v);
            max = Math.max(max, v);
            sum += v;
        }
        int avg = Math.round((float) sum / values.size());

        int tasksDone = energyLogService.countByReason(deviceId, roleId, start, end, EnergyLogService.REASON_TASK);

        // 本周新得徽章
        List<Map<String, Object>> newBadges = new ArrayList<>();
        for (AgentBadgeDO b : badgeService.list(deviceId, roleId)) {
            if (b.getEarnDate() != null && !b.getEarnDate().isBefore(start) && !b.getEarnDate().isAfter(end)) {
                BadgeCatalog.Badge meta = BadgeCatalog.get(b.getBadgeKey());
                if (meta != null) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("key", meta.key());
                    m.put("label", meta.label());
                    m.put("icon", meta.icon());
                    newBadges.add(m);
                }
            }
        }

        // 本周新记住/更新的档案
        List<Map<String, String>> newMemories = new ArrayList<>();
        for (AgentProfileDO p : agentProfileService.list(deviceId, roleId)) {
            LocalDate touched = p.getUpdateTime() != null ? p.getUpdateTime().toLocalDate()
                    : (p.getCreateTime() != null ? p.getCreateTime().toLocalDate() : null);
            if (touched != null && !touched.isBefore(start) && !touched.isAfter(end)
                    && agentProfileService.isAllowedKey(p.getFieldKey())) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("label", AgentProfileService.PROFILE_LABELS.getOrDefault(p.getFieldKey(), p.getFieldKey()));
                m.put("value", p.getFieldValue());
                newMemories.add(m);
                if (newMemories.size() >= MAX_NEW_MEMORIES) {
                    break;
                }
            }
        }

        int companionDays = state.getCompanionDays() == null ? 0 : state.getCompanionDays();
        CompanionStage stage = CompanionStage.fromDays(companionDays);
        Map<String, Object> stageView = new LinkedHashMap<>();
        stageView.put("level", stage.level());
        stageView.put("name", stage.label());

        ScriptLineDO closing = scriptLineService.randomLine("weekly_report_closing");
        String highlight = closing != null ? closing.getContent() : "这一周就先收进星球档案啦，下一周我们慢慢来。";

        return new Report(start, end,
                values.get(0), values.get(values.size() - 1), avg, min, max,
                values, curve, tasksDone,
                state.getStreakDays() == null ? 0 : state.getStreakDays(),
                state.getBestStreak() == null ? 0 : state.getBestStreak(),
                companionDays, stageView, newBadges, newMemories, highlight);
    }

    // ---------------- 视图 / 落库转换 ----------------

    private Map<String, Object> toApiMap(Report r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodStart", r.periodStart().toString());
        m.put("periodEnd", r.periodEnd().toString());
        m.put("energyStart", r.energyStart());
        m.put("energyEnd", r.energyEnd());
        m.put("energyAvg", r.energyAvg());
        m.put("energyMin", r.energyMin());
        m.put("energyMax", r.energyMax());
        m.put("energyCurve", r.energyCurve());
        m.put("tasksDone", r.tasksDone());
        m.put("streakDays", r.streakDays());
        m.put("bestStreak", r.bestStreak());
        m.put("companionDays", r.companionDays());
        m.put("stage", r.stage());
        m.put("badgesEarned", r.newBadges().size());
        m.put("newBadges", r.newBadges());
        m.put("newMemories", r.newMemories());
        m.put("highlight", r.highlight());
        return m;
    }

    private WeeklyReportDO toDO(String deviceId, Integer roleId, Report r) {
        WeeklyReportDO d = new WeeklyReportDO();
        d.setDeviceId(deviceId);
        d.setRoleId(roleId);
        d.setPeriodStart(r.periodStart());
        d.setPeriodEnd(r.periodEnd());
        d.setEnergyStart(r.energyStart());
        d.setEnergyEnd(r.energyEnd());
        d.setEnergyAvg(r.energyAvg());
        d.setEnergyMin(r.energyMin());
        d.setEnergyMax(r.energyMax());
        d.setEnergyCurve(writeJson(r.values()));
        d.setTasksDone(r.tasksDone());
        d.setStreakDays(r.streakDays());
        d.setCompanionDays(r.companionDays());
        d.setStage(r.stage().get("level") instanceof Integer lv ? lv : 1);
        d.setBadgesEarned(r.newBadges().size());
        d.setNewMemories(writeJson(r.newMemories()));
        d.setHighlight(r.highlight());
        return d;
    }

    /** 归档记录 → API 视图（历史回看）。 */
    private Map<String, Object> fromDO(WeeklyReportDO d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("periodStart", d.getPeriodStart() != null ? d.getPeriodStart().toString() : null);
        m.put("periodEnd", d.getPeriodEnd() != null ? d.getPeriodEnd().toString() : null);
        m.put("energyStart", d.getEnergyStart());
        m.put("energyEnd", d.getEnergyEnd());
        m.put("energyAvg", d.getEnergyAvg());
        m.put("energyMin", d.getEnergyMin());
        m.put("energyMax", d.getEnergyMax());
        List<Integer> vals = readIntList(d.getEnergyCurve());
        List<Map<String, Object>> curve = new ArrayList<>();
        LocalDate start = d.getPeriodStart();
        for (int i = 0; i < vals.size(); i++) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("date", start != null ? start.plusDays(i).format(MD) : String.valueOf(i + 1));
            p.put("energy", vals.get(i));
            curve.add(p);
        }
        m.put("energyCurve", curve);
        m.put("tasksDone", d.getTasksDone());
        m.put("streakDays", d.getStreakDays());
        m.put("companionDays", d.getCompanionDays());
        Map<String, Object> stage = new LinkedHashMap<>();
        int lv = d.getStage() == null ? 1 : d.getStage();
        stage.put("level", lv);
        stage.put("name", CompanionStage.fromLevel(lv).label());
        m.put("stage", stage);
        m.put("badgesEarned", d.getBadgesEarned());
        m.put("newMemories", readMemoryList(d.getNewMemories()));
        m.put("highlight", d.getHighlight());
        return m;
    }

    private String writeJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("周报字段序列化失败", e);
            return null;
        }
    }

    private List<Integer> readIntList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Integer>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<Map<String, String>> readMemoryList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<List<Map<String, String>>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 取 <= d 的最近一个周日（含当天）。 */
    private LocalDate mostRecentSunday(LocalDate d) {
        int back = d.getDayOfWeek().getValue() % 7; // Sun→0, Mon→1, ... Sat→6
        return d.minusDays(back);
    }

    /** 周报内部结构（values 为纯能量序列，用于落库；energyCurve 带日期，用于展示）。 */
    private record Report(
            LocalDate periodStart, LocalDate periodEnd,
            Integer energyStart, Integer energyEnd, Integer energyAvg, Integer energyMin, Integer energyMax,
            List<Integer> values, List<Map<String, Object>> energyCurve,
            int tasksDone, int streakDays, int bestStreak,
            int companionDays, Map<String, Object> stage,
            List<Map<String, Object>> newBadges, List<Map<String, String>> newMemories,
            String highlight) {
    }
}
