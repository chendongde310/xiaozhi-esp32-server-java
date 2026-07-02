package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentBadgeDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.AgentBadgeMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 星球徽章授予与查询服务。授予以 (deviceId, roleId, badgeKey) 唯一键保证幂等。
 */
@Slf4j
@Service
public class BadgeService {

    @Resource
    private AgentBadgeMapper agentBadgeMapper;

    /** 已获得的徽章键集合。 */
    public Set<String> earnedKeys(String deviceId, Integer roleId) {
        Set<String> keys = new LinkedHashSet<>();
        for (AgentBadgeDO b : list(deviceId, roleId)) {
            keys.add(b.getBadgeKey());
        }
        return keys;
    }

    /** 已获得的徽章记录，按获得时间升序。 */
    public List<AgentBadgeDO> list(String deviceId, Integer roleId) {
        return agentBadgeMapper.selectList(new LambdaQueryWrapper<AgentBadgeDO>()
                .eq(AgentBadgeDO::getDeviceId, deviceId)
                .eq(AgentBadgeDO::getRoleId, roleId)
                .orderByAsc(AgentBadgeDO::getCreateTime));
    }

    public int count(String deviceId, Integer roleId) {
        return Math.toIntExact(agentBadgeMapper.selectCount(new LambdaQueryWrapper<AgentBadgeDO>()
                .eq(AgentBadgeDO::getDeviceId, deviceId)
                .eq(AgentBadgeDO::getRoleId, roleId)));
    }

    /**
     * 授予单枚徽章。
     *
     * @return true 表示本次新授予；false 表示此前已有或键非法。
     */
    public boolean award(String deviceId, Integer roleId, String badgeKey) {
        if (!BadgeCatalog.isKnown(badgeKey)) {
            return false;
        }
        AgentBadgeDO row = new AgentBadgeDO();
        row.setDeviceId(deviceId);
        row.setRoleId(roleId);
        row.setBadgeKey(badgeKey);
        row.setEarnDate(LocalDate.now());
        row.setCreateTime(LocalDateTime.now());
        try {
            agentBadgeMapper.insert(row);
            return true;
        } catch (DuplicateKeyException e) {
            return false; // 已获得，幂等
        } catch (Exception e) {
            log.warn("授予徽章失败 deviceId={}, roleId={}, badge={}", deviceId, roleId, badgeKey, e);
            return false;
        }
    }

    /**
     * 批量授予，返回本次“新授予”的徽章键（保持入参顺序），供触发授勋仪式与前端提示。
     */
    public List<String> awardMany(String deviceId, Integer roleId, List<String> badgeKeys) {
        List<String> fresh = new ArrayList<>();
        if (badgeKeys == null) {
            return fresh;
        }
        for (String key : badgeKeys) {
            if (award(deviceId, roleId, key)) {
                fresh.add(key);
            }
        }
        return fresh;
    }
}
