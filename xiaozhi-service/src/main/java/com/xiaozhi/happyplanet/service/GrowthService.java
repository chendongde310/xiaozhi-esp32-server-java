package com.xiaozhi.happyplanet.service;

import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 成长仪式编排：在设备重连时“认领并结算”应播报一次的里程碑仪式——羁绊阶段升级与陪伴纪念日。
 *
 * <p>展示用的当前阶段始终由 {@link CompanionStage#fromDays} 实时推导；本服务通过持久化
 * {@code sys_agent_state.stage / lastAnniversary} 做仪式去重，保证每个里程碑只庆祝一次。
 * 仪式台词逐字取自 {@code sys_script_lines}，播报动作由 dialogue 侧 {@code HappyPlanetSpeaker} 执行，
 * 本服务只产出“该播哪些场景 + 该发哪些徽章”，不依赖 dialogue，故可被两个进程复用。
 */
@Slf4j
@Service
public class GrowthService {

    @Resource
    private AgentStateService agentStateService;
    @Resource
    private BadgeService badgeService;

    /** 连接仪式计划：按顺序逐字播报的场景键 + 本次新授予的徽章键。 */
    public record RitualPlan(List<String> sceneKeys, List<String> newBadges) {
        public boolean isEmpty() {
            return sceneKeys.isEmpty() && newBadges.isEmpty();
        }
    }

    private static final RitualPlan EMPTY = new RitualPlan(List.of(), List.of());

    /**
     * 认领并结算“连接时应播报一次”的成长仪式。幂等：多次调用只在里程碑首次达成时返回内容。
     */
    public RitualPlan claimConnectRituals(String deviceId, Integer roleId) {
        try {
            AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
            int days = nz(state.getCompanionDays());
            List<String> scenes = new ArrayList<>();
            List<String> badgeKeys = new ArrayList<>();
            boolean dirty = false;

            // —— 羁绊阶段升级（可能一次跨多级，逐级播报升级仪式）——
            CompanionStage current = CompanionStage.fromDays(days);
            int celebratedStage = state.getStage() == null ? 1 : state.getStage();
            if (current.level() > celebratedStage) {
                for (int lvl = celebratedStage + 1; lvl <= current.level(); lvl++) {
                    CompanionStage s = CompanionStage.fromLevel(lvl);
                    if (s.stageUpSceneKey() != null) {
                        scenes.add(s.stageUpSceneKey());
                    }
                    if (s.badgeKey() != null) {
                        badgeKeys.add(s.badgeKey());
                    }
                }
                state.setStage(current.level());
                dirty = true;
            }

            // —— 陪伴纪念日（取已跨过且尚未庆祝的最大里程碑，一次只庆祝一个，避免堆叠）——
            int celebratedAnn = nz(state.getLastAnniversary());
            int milestone = 0;
            for (int m : BadgeCatalog.ANNIVERSARY_MILESTONES) {
                if (days >= m && m > celebratedAnn && m > milestone) {
                    milestone = m;
                }
            }
            if (milestone > 0) {
                scenes.add("anniversary_" + milestone);
                badgeKeys.add(BadgeCatalog.anniversaryBadge(milestone));
                state.setLastAnniversary(milestone);
                dirty = true;
            }

            if (!dirty) {
                return EMPTY;
            }
            agentStateService.save(state);
            List<String> fresh = badgeService.awardMany(deviceId, roleId, badgeKeys);
            return new RitualPlan(scenes, fresh);
        } catch (Exception e) {
            log.warn("认领成长仪式失败 deviceId={}, roleId={}", deviceId, roleId, e);
            return EMPTY;
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
