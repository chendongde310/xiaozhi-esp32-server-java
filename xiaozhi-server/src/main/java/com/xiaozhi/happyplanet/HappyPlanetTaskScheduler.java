package com.xiaozhi.happyplanet;

import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.service.AgentRuntimeService;
import com.xiaozhi.happyplanet.service.AgentStateService;
import com.xiaozhi.happyplanet.service.WeeklyReportService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 快乐星球每日任务调度。
 *
 * <p>放在 xiaozhi-server（单实例）而非可横向扩展的 dialogue，避免多实例重复执行。
 * 分配本身是幂等的（{@link AgentStateService#ensureDailyTask}），且对话侧还有懒加载兜底，
 * 因此调度失败或错过也不影响正确性。
 */
@Slf4j
@Component
public class HappyPlanetTaskScheduler {

    @Resource
    private AgentStateService agentStateService;

    @Resource
    private WeeklyReportService weeklyReportService;

    /** 每天早上 8 点为所有活跃智能体分配当日星球任务。 */
    @Scheduled(cron = "0 0 8 * * ?")
    public void assignDailyTasks() {
        List<AgentStateDO> states = agentStateService.listAll();
        int count = 0;
        for (AgentStateDO state : states) {
            try {
                agentStateService.ensureDailyTask(state.getDeviceId(), state.getRoleId(),
                        AgentRuntimeService.DEFAULT_AUDIENCE);
                count++;
            } catch (Exception e) {
                log.warn("为智能体分配当日星球任务失败 deviceId={}, roleId={}",
                        state.getDeviceId(), state.getRoleId(), e);
            }
        }
        log.info("========== 快乐星球每日任务分配完成，共 {} 个智能体 ==========", count);
    }

    /**
     * 每周日 21:00 为所有活跃智能体生成并归档“本自然周（周一~周日）”的星球周报。
     * 生成幂等（按 deviceId+roleId+periodStart 唯一），错过或重跑都安全。
     */
    @Scheduled(cron = "0 0 21 ? * SUN")
    public void generateWeeklyReports() {
        List<AgentStateDO> states = agentStateService.listAll();
        int count = 0;
        for (AgentStateDO state : states) {
            try {
                weeklyReportService.generateForLastWeek(state.getDeviceId(), state.getRoleId());
                count++;
            } catch (Exception e) {
                log.warn("生成星球周报失败 deviceId={}, roleId={}",
                        state.getDeviceId(), state.getRoleId(), e);
            }
        }
        log.info("========== 快乐星球周报生成完成，共 {} 个智能体 ==========", count);
    }
}
