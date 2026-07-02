package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentBadgeDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.EnergyLogDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlanetTaskDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.AgentBadgeMapper;
import com.xiaozhi.happyplanet.dal.mysql.mapper.EnergyLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 演示数据填充：为指定 (deviceId, roleId) 一键灌入一份可信的成长档案，
 * 方便在没有真实积累时演示羁绊阶段 / 连击 / 徽章 / 能量曲线 / 周报 / 记忆星图。
 *
 * <p>幂等：每次填充会先清空该智能体的徽章与能量流水再重灌。仅供演示，不改变安全边界。
 */
@Slf4j
@Service
public class DemoSeedService {

    @Resource
    private AgentStateService agentStateService;
    @Resource
    private PlanetTaskService planetTaskService;
    @Resource
    private AgentProfileService agentProfileService;
    @Resource
    private EnergyLogMapper energyLogMapper;
    @Resource
    private AgentBadgeMapper agentBadgeMapper;

    /** 近 7 天每日“最后能量值”，用于画出一条有起伏的曲线。 */
    private static final int[] DAILY_ENERGY = {72, 78, 68, 85, 90, 82, 84};
    /** 这几天完成了星球任务（index 对应近 7 天，0=最早）。 */
    private static final Set<Integer> TASK_DAYS = Set.of(0, 1, 3, 4, 6);

    @Transactional
    public void seed(String deviceId, Integer roleId) {
        LocalDate today = LocalDate.now();

        // 1) 运行状态：陪伴 30 天(→星球密友)、连击 8、能量 84。
        //    stage/lastAnniversary 故意留在上一档，这样若用真实设备“重连”，会触发一次
        //    “星球密友”升级仪式 + “相伴一月”纪念仪式；纯 App 演示则不受影响（阶段按天数实时推导）。
        AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
        state.setEnergy(84);
        state.setEnergyUpdateTime(LocalDateTime.now());
        state.setCompanionDays(30);
        state.setFirstConnected(1);
        state.setFirstInteractDate(today.minusDays(30));
        state.setLastInteractDate(today);
        state.setStreakDays(8);
        state.setBestStreak(12);
        state.setStreakRepairLeft(1);
        state.setStreakRepairMonth(YearMonth.now().toString());
        state.setLastStreakDate(today);
        state.setStage(3);
        state.setLastAnniversary(7);
        state.setCurrentChannel(AgentStateService.CHANNEL_DAY);
        PlanetTaskDO task = planetTaskService.randomTask("all");
        if (task != null) {
            state.setCurrentTaskId(task.getId());
            state.setLastTaskDate(today);
            state.setCurrentTaskDone(0);
        }
        agentStateService.save(state);

        // 2) 档案：此时已是星球密友，全部字段解锁，点亮记忆星图。
        Map<String, String> profile = new LinkedHashMap<>();
        profile.put("nickname", "小柚");
        profile.put("preferredCall", "老朋友");
        profile.put("companionStyle", "安静、慢一点");
        profile.put("activeTime", "晚饭后");
        profile.put("storyType", "宇宙冒险");
        profile.put("storyLength", "short");
        profile.put("hobby", "看星星");
        profile.put("favoriteFood", "关东煮");
        profile.put("comfortTopic", "小时候的动画片");
        profile.forEach((k, v) -> {
            try {
                agentProfileService.updateField(null, deviceId, roleId, k, v);
            } catch (Exception e) {
                log.debug("演示档案写入跳过 key={}", k);
            }
        });

        // 3) 徽章：清空后按目录发放，带历史日期（streak_7 落在本周 → 周报“本周新徽章”）。
        agentBadgeMapper.delete(new LambdaQueryWrapper<AgentBadgeDO>()
                .eq(AgentBadgeDO::getDeviceId, deviceId)
                .eq(AgentBadgeDO::getRoleId, roleId));
        insertBadge(deviceId, roleId, "streak_3", today.minusDays(12));
        insertBadge(deviceId, roleId, "streak_7", today.minusDays(2));
        insertBadge(deviceId, roleId, "stage_familiar", today.minusDays(27));
        insertBadge(deviceId, roleId, "stage_oldfriend", today.minusDays(20));
        insertBadge(deviceId, roleId, "day_7", today.minusDays(23));

        // 4) 能量流水：近 7 天，每天一条互动（当天最后值=曲线点）+ 5 天完成任务。
        energyLogMapper.delete(new LambdaQueryWrapper<EnergyLogDO>()
                .eq(EnergyLogDO::getDeviceId, deviceId)
                .eq(EnergyLogDO::getRoleId, roleId));
        int prev = DAILY_ENERGY[0];
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(6L - i);
            int target = DAILY_ENERGY[i];
            if (TASK_DAYS.contains(i)) {
                insertEnergy(deviceId, roleId, Math.max(0, target - 4), 12, EnergyLogService.REASON_TASK,
                        d, d.atTime(12, 30));
            }
            insertEnergy(deviceId, roleId, target, target - prev, EnergyLogService.REASON_INTERACTION,
                    d, d.atTime(20, 10));
            prev = target;
        }
    }

    private void insertBadge(String deviceId, Integer roleId, String key, LocalDate earn) {
        AgentBadgeDO b = new AgentBadgeDO();
        b.setDeviceId(deviceId);
        b.setRoleId(roleId);
        b.setBadgeKey(key);
        b.setEarnDate(earn);
        b.setCreateTime(earn.atTime(10, 0));
        agentBadgeMapper.insert(b);
    }

    private void insertEnergy(String deviceId, Integer roleId, int energy, int delta, String reason,
                             LocalDate date, LocalDateTime time) {
        EnergyLogDO e = new EnergyLogDO();
        e.setDeviceId(deviceId);
        e.setRoleId(roleId);
        e.setEnergy(energy);
        e.setDelta(delta);
        e.setReason(reason);
        e.setLogDate(date);
        e.setLogTime(time);
        energyLogMapper.insert(e);
    }
}
