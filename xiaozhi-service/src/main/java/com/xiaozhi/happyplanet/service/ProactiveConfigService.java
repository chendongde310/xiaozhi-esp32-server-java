package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ProactiveConfigDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.ProactiveConfigMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalTime;

/**
 * 待命主动搭话配置读写服务，按 (deviceId, roleId) 唯一。
 *
 * <p>默认常量与迁移 V15__proactive_chat.sql 的列 DEFAULT 保持一致，用于代码侧创建行时兜底。
 * 总开关默认关闭：无对应行即视为关闭，创建行也不会自动开启，必须显式 {@link #setEnabled} 才生效。
 */
@Slf4j
@Service
public class ProactiveConfigService {

    // —— 默认值：与 V15 迁移 DEFAULT 对齐（“适中”策略）——
    public static final int DEFAULT_DAILY_LIMIT = 3;
    public static final int DEFAULT_COOLDOWN_MINUTES = 90;
    public static final int DEFAULT_MIN_IDLE_SECONDS = 600;
    public static final LocalTime DEFAULT_ACTIVE_START = LocalTime.of(9, 0);
    public static final LocalTime DEFAULT_ACTIVE_END = LocalTime.of(21, 0);
    public static final LocalTime DEFAULT_QUIET_START = LocalTime.of(22, 0);
    public static final LocalTime DEFAULT_QUIET_END = LocalTime.of(8, 0);

    @Resource
    private ProactiveConfigMapper proactiveConfigMapper;

    /** 取配置；不存在返回 null（视为关闭）。 */
    public ProactiveConfigDO find(String deviceId, Integer roleId) {
        if (deviceId == null || roleId == null) {
            return null;
        }
        return proactiveConfigMapper.selectOne(new LambdaQueryWrapper<ProactiveConfigDO>()
                .eq(ProactiveConfigDO::getDeviceId, deviceId)
                .eq(ProactiveConfigDO::getRoleId, roleId)
                .last("limit 1"));
    }

    /** 开关是否开启（无配置行 = 关闭）。 */
    public boolean isEnabled(String deviceId, Integer roleId) {
        ProactiveConfigDO cfg = find(deviceId, roleId);
        return cfg != null && Integer.valueOf(1).equals(cfg.getEnabled());
    }

    /** 取配置；不存在则以默认值创建一行（enabled=0，不自动开启）。返回已填默认值的对象。 */
    public ProactiveConfigDO getOrCreate(String deviceId, Integer roleId) {
        ProactiveConfigDO cfg = find(deviceId, roleId);
        if (cfg == null) {
            cfg = new ProactiveConfigDO();
            cfg.setDeviceId(deviceId);
            cfg.setRoleId(roleId);
            cfg.setEnabled(0);
            applyDefaults(cfg);
            try {
                proactiveConfigMapper.insert(cfg);
            } catch (DuplicateKeyException e) {
                cfg = find(deviceId, roleId);
            }
        }
        applyDefaults(cfg);
        return cfg;
    }

    /** 设置总开关（语音指令 / 控制台 / APP 共用）。 */
    public void setEnabled(String deviceId, Integer roleId, boolean enabled) {
        ProactiveConfigDO cfg = getOrCreate(deviceId, roleId);
        cfg.setEnabled(enabled ? 1 : 0);
        proactiveConfigMapper.updateById(cfg);
    }

    /**
     * 批量更新可控变量（仅更新非 null 字段），供控制台/APP 调参。不改变 enabled 语义之外的行为。
     */
    public ProactiveConfigDO update(String deviceId, Integer roleId, ProactiveConfigDO patch) {
        ProactiveConfigDO cfg = getOrCreate(deviceId, roleId);
        if (patch.getEnabled() != null) cfg.setEnabled(patch.getEnabled());
        if (patch.getAllowLlm() != null) cfg.setAllowLlm(patch.getAllowLlm());
        if (patch.getDailyLimit() != null) cfg.setDailyLimit(patch.getDailyLimit());
        if (patch.getCooldownMinutes() != null) cfg.setCooldownMinutes(patch.getCooldownMinutes());
        if (patch.getMinIdleSeconds() != null) cfg.setMinIdleSeconds(patch.getMinIdleSeconds());
        if (patch.getActiveStart() != null) cfg.setActiveStart(patch.getActiveStart());
        if (patch.getActiveEnd() != null) cfg.setActiveEnd(patch.getActiveEnd());
        if (patch.getQuietStart() != null) cfg.setQuietStart(patch.getQuietStart());
        if (patch.getQuietEnd() != null) cfg.setQuietEnd(patch.getQuietEnd());
        proactiveConfigMapper.updateById(cfg);
        return cfg;
    }

    /** 为 null 的字段填入默认值（防止旧行/半初始化行导致 NPE）。 */
    public void applyDefaults(ProactiveConfigDO cfg) {
        if (cfg == null) {
            return;
        }
        if (cfg.getEnabled() == null) cfg.setEnabled(0);
        if (cfg.getAllowLlm() == null) cfg.setAllowLlm(1);
        if (cfg.getDailyLimit() == null) cfg.setDailyLimit(DEFAULT_DAILY_LIMIT);
        if (cfg.getCooldownMinutes() == null) cfg.setCooldownMinutes(DEFAULT_COOLDOWN_MINUTES);
        if (cfg.getMinIdleSeconds() == null) cfg.setMinIdleSeconds(DEFAULT_MIN_IDLE_SECONDS);
        if (cfg.getActiveStart() == null) cfg.setActiveStart(DEFAULT_ACTIVE_START);
        if (cfg.getActiveEnd() == null) cfg.setActiveEnd(DEFAULT_ACTIVE_END);
        if (cfg.getQuietStart() == null) cfg.setQuietStart(DEFAULT_QUIET_START);
        if (cfg.getQuietEnd() == null) cfg.setQuietEnd(DEFAULT_QUIET_END);
    }
}
