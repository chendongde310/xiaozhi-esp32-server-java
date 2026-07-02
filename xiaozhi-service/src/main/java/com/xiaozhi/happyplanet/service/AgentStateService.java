package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlanetTaskDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.AgentStateMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 智能体运行时状态（快乐能量 / 陪伴天数 / 首连标记 / 当前频道）持久化服务。
 *
 * <p>状态以 (deviceId, roleId) 唯一。能量衰减采用"读时惰性衰减"，无需依赖定时任务即可保证正确，
 * 避免多实例定时器重复执行的问题。
 */
@Slf4j
@Service
public class AgentStateService {

    public static final String CHANNEL_DAY = "day";
    public static final String CHANNEL_NIGHT = "night";
    public static final String CHANNEL_CHILDHOOD = "childhood";
    public static final String CHANNEL_ENERGY_REPAIR = "energy_repair";

    /** 每月发放的能量修复卡（补签卡）张数 */
    public static final int MONTHLY_REPAIR_CARDS = 1;

    @Resource
    private AgentStateMapper agentStateMapper;

    @Resource
    private EnergyService energyService;

    @Resource
    private PlanetTaskService planetTaskService;

    @Resource
    private EnergyLogService energyLogService;

    @Resource
    private BadgeService badgeService;

    /** markTaskDone 结算结果：是否标记成功、是否用补签卡续连、本次新获得的徽章键。 */
    public record TaskDoneResult(boolean marked, boolean repaired, java.util.List<String> newBadges) {
        public static TaskDoneResult miss() {
            return new TaskDoneResult(false, false, java.util.List.of());
        }
    }

    public AgentStateDO findOne(String deviceId, Integer roleId) {
        return agentStateMapper.selectOne(new LambdaQueryWrapper<AgentStateDO>()
                .eq(AgentStateDO::getDeviceId, deviceId)
                .eq(AgentStateDO::getRoleId, roleId)
                .last("limit 1"));
    }

    /** 取状态；不存在则以默认值创建。读取时应用惰性衰减。 */
    public AgentStateDO getOrCreate(String deviceId, Integer roleId) {
        AgentStateDO state = findOne(deviceId, roleId);
        if (state == null) {
            state = new AgentStateDO();
            state.setDeviceId(deviceId);
            state.setRoleId(roleId);
            state.setEnergy(EnergyService.DEFAULT_ENERGY);
            state.setEnergyUpdateTime(LocalDateTime.now());
            state.setCompanionDays(0);
            state.setFirstConnected(0);
            state.setCurrentChannel(CHANNEL_DAY);
            state.setCurrentTaskDone(0);
            // 成长体系初值（与 DB 默认一致，保证内存对象自洽）
            state.setStreakDays(0);
            state.setBestStreak(0);
            state.setStreakRepairLeft(MONTHLY_REPAIR_CARDS);
            state.setStreakRepairMonth(YearMonth.now().toString());
            state.setStage(1);
            state.setLastAnniversary(0);
            try {
                agentStateMapper.insert(state);
            } catch (DuplicateKeyException e) {
                state = findOne(deviceId, roleId);
            }
        }
        applyLazyDecay(state);
        refillRepairIfNewMonth(state);
        return state;
    }

    /** 跨月自动补充能量修复卡（补签卡）。仅在月份变化时写一次。 */
    private void refillRepairIfNewMonth(AgentStateDO state) {
        if (state == null || state.getId() == null) {
            return;
        }
        String month = YearMonth.now().toString();
        if (!month.equals(state.getStreakRepairMonth())) {
            state.setStreakRepairMonth(month);
            state.setStreakRepairLeft(MONTHLY_REPAIR_CARDS);
            agentStateMapper.updateById(state);
        }
    }

    /** 若距上次能量更新已跨天且无互动，按天衰减能量（有下限），并持久化。 */
    private void applyLazyDecay(AgentStateDO state) {
        if (state == null || state.getEnergyUpdateTime() == null || state.getEnergy() == null) {
            return;
        }
        LocalDate last = state.getEnergyUpdateTime().toLocalDate();
        LocalDate today = LocalDate.now();
        long days = ChronoUnit.DAYS.between(last, today);
        if (days <= 0) {
            return;
        }
        // 衰减只会降低能量：不高于当前值，也不低于下限。能量本就低于下限时保持不变（不得被"抬高"到下限）。
        int target = state.getEnergy() - (int) days * EnergyService.DECAY_PER_DAY;
        int decayed = Math.min(state.getEnergy(), Math.max(EnergyService.DECAY_FLOOR, target));
        if (decayed != state.getEnergy()) {
            int applied = decayed - state.getEnergy();
            state.setEnergy(decayed);
            state.setEnergyUpdateTime(LocalDateTime.now());
            agentStateMapper.updateById(state);
            energyLogService.record(state.getDeviceId(), state.getRoleId(), decayed, applied, EnergyLogService.REASON_DECAY);
        }
    }

    /**
     * 原子地认领首连仪式：仅当 firstConnected=0 时置为 1。
     *
     * @return true 表示本次调用抢到了首连（应播放首连仪式）；false 表示此前已完成过。
     */
    public boolean claimFirstConnect(String deviceId, Integer roleId) {
        getOrCreate(deviceId, roleId);
        int updated = agentStateMapper.update(null, new LambdaUpdateWrapper<AgentStateDO>()
                .eq(AgentStateDO::getDeviceId, deviceId)
                .eq(AgentStateDO::getRoleId, roleId)
                .eq(AgentStateDO::getFirstConnected, 0)
                .set(AgentStateDO::getFirstConnected, 1));
        return updated == 1;
    }

    public void setCurrentChannel(String deviceId, Integer roleId, String channel) {
        getOrCreate(deviceId, roleId);
        agentStateMapper.update(null, new LambdaUpdateWrapper<AgentStateDO>()
                .eq(AgentStateDO::getDeviceId, deviceId)
                .eq(AgentStateDO::getRoleId, roleId)
                .set(AgentStateDO::getCurrentChannel, channel));
    }

    /**
     * 处理一次用户互动：能量增量 + 陪伴天数 + 当天任务分配。就地修改并持久化传入的 state。
     */
    public void recordInteraction(AgentStateDO state, int energyDelta, String audience) {
        LocalDate today = LocalDate.now();
        int oldEnergy = nz(state.getEnergy(), EnergyService.DEFAULT_ENERGY);
        int newEnergy = energyService.clamp(oldEnergy + energyDelta);
        state.setEnergy(newEnergy);
        state.setEnergyUpdateTime(LocalDateTime.now());

        if (state.getFirstInteractDate() == null) {
            state.setFirstInteractDate(today);
        }
        if (!today.equals(state.getLastInteractDate())) {
            state.setCompanionDays(nz(state.getCompanionDays(), 0) + 1);
            state.setLastInteractDate(today);
        }
        assignDailyTaskIfNeeded(state, audience, today);
        save(state);
        energyLogService.record(state.getDeviceId(), state.getRoleId(), newEnergy,
                newEnergy - oldEnergy, EnergyLogService.REASON_INTERACTION);
    }

    /** 当天尚未分配任务则随机分配一条。就地修改 state（不单独持久化，由调用方 save）。 */
    public void assignDailyTaskIfNeeded(AgentStateDO state, String audience, LocalDate today) {
        if (today.equals(state.getLastTaskDate()) && state.getCurrentTaskId() != null) {
            return;
        }
        PlanetTaskDO task = planetTaskService.randomTask(audience);
        if (task != null) {
            state.setCurrentTaskId(task.getId());
            state.setLastTaskDate(today);
            state.setCurrentTaskDone(0);
        }
    }

    /**
     * 标记今日任务完成：给予能量奖励，结算任务连击（断一天可用补签卡自动续连），并授予达成的连击徽章。
     *
     * @param taskId 允许为 null（表示"完成当前任务"）；非 null 时需与 currentTaskId 一致才生效。
     * @return 结算结果（是否标记成功、是否用补签卡续连、本次新得徽章）。
     */
    public TaskDoneResult markTaskDone(String deviceId, Integer roleId, Long taskId) {
        AgentStateDO state = getOrCreate(deviceId, roleId);
        if (state.getCurrentTaskId() == null) {
            return TaskDoneResult.miss();
        }
        if (taskId != null && !taskId.equals(state.getCurrentTaskId())) {
            return TaskDoneResult.miss();
        }
        if (Integer.valueOf(1).equals(state.getCurrentTaskDone())) {
            return new TaskDoneResult(true, false, List.of()); // 已完成，幂等：不重复结算
        }
        int oldEnergy = nz(state.getEnergy(), EnergyService.DEFAULT_ENERGY);
        int newEnergy = energyService.clamp(oldEnergy + EnergyService.TASK_DONE_BONUS);
        state.setCurrentTaskDone(1);
        state.setEnergy(newEnergy);
        state.setEnergyUpdateTime(LocalDateTime.now());

        boolean repaired = settleStreakOnTaskDone(state);
        save(state);

        energyLogService.record(deviceId, roleId, newEnergy, newEnergy - oldEnergy, EnergyLogService.REASON_TASK);
        List<String> newBadges = badgeService.awardMany(deviceId, roleId,
                BadgeCatalog.streakBadgesFor(nz(state.getStreakDays(), 0)));
        return new TaskDoneResult(true, repaired, newBadges);
    }

    /**
     * 结算连续完成任务的连击。断整一天且有补签卡则自动续连；断更久或无卡则从 1 重新开始。
     * 就地修改 streakDays / bestStreak / lastStreakDate / streakRepairLeft，由调用方 save。
     *
     * @return 是否使用了补签卡续连
     */
    private boolean settleStreakOnTaskDone(AgentStateDO state) {
        LocalDate today = LocalDate.now();
        LocalDate last = state.getLastStreakDate();
        int prev = nz(state.getStreakDays(), 0);
        boolean repaired = false;
        int streak;
        if (last == null) {
            streak = 1;
        } else {
            long gap = ChronoUnit.DAYS.between(last, today);
            if (gap <= 0) {
                streak = Math.max(prev, 1); // 当日重复（幂等已拦截，稳妥兜底）
            } else if (gap == 1) {
                streak = prev + 1; // 连续
            } else if (gap == 2 && nz(state.getStreakRepairLeft(), 0) > 0) {
                state.setStreakRepairLeft(state.getStreakRepairLeft() - 1);
                repaired = true;
                streak = prev + 1; // 断一天，用补签卡续上
            } else {
                streak = 1; // 断签超过一天或无补签卡，重新开始
            }
        }
        state.setStreakDays(streak);
        state.setLastStreakDate(today);
        state.setBestStreak(Math.max(nz(state.getBestStreak(), 0), streak));
        return repaired;
    }

    public void save(AgentStateDO state) {
        if (state.getId() == null) {
            try {
                agentStateMapper.insert(state);
            } catch (DuplicateKeyException e) {
                AgentStateDO existing = findOne(state.getDeviceId(), state.getRoleId());
                if (existing != null) {
                    state.setId(existing.getId());
                    agentStateMapper.updateById(state);
                }
            }
        } else {
            agentStateMapper.updateById(state);
        }
    }

    /** 确保当天已分配星球任务（供定时任务/懒加载调用，幂等）。 */
    public void ensureDailyTask(String deviceId, Integer roleId, String audience) {
        AgentStateDO state = getOrCreate(deviceId, roleId);
        LocalDate today = LocalDate.now();
        if (today.equals(state.getLastTaskDate()) && state.getCurrentTaskId() != null) {
            return;
        }
        assignDailyTaskIfNeeded(state, audience, today);
        save(state);
    }

    /** 列出全部智能体状态（供管理端/定时任务）。 */
    public List<AgentStateDO> listAll() {
        return agentStateMapper.selectList(new LambdaQueryWrapper<AgentStateDO>()
                .orderByDesc(AgentStateDO::getUpdateTime));
    }

    // ========== 待命主动搭话运行时护栏 ==========

    /** 记录一次主动搭话：跨天归零计数，次数+1，刷新时间。就地更新并持久化。 */
    public void recordProactive(String deviceId, Integer roleId, LocalDateTime now) {
        AgentStateDO state = getOrCreate(deviceId, roleId);
        LocalDate today = now.toLocalDate();
        if (!today.equals(state.getProactiveDate())) {
            state.setProactiveDate(today);
            state.setProactiveCount(0);
            state.setProactiveIgnoredToday(0);
        }
        state.setProactiveCount(nz(state.getProactiveCount(), 0) + 1);
        state.setLastProactiveTime(now);
        save(state);
    }

    /** 标记当日主动搭话被忽略（当天退避）。仅当计数日期为今日才生效。 */
    public void markProactiveIgnored(String deviceId, Integer roleId, LocalDate today) {
        AgentStateDO state = getOrCreate(deviceId, roleId);
        if (today.equals(state.getProactiveDate())) {
            state.setProactiveIgnoredToday(1);
            save(state);
        }
    }

    private int nz(Integer v, int def) {
        return v == null ? def : v;
    }
}
