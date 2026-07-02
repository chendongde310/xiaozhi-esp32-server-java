package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentProfileDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.AgentProfileMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快乐星球档案（结构化长期记忆）服务。
 *
 * <p>字段键受白名单约束，只允许记录甲方 Demo 文档"可记忆内容"列出的偏好项，
 * 从代码层面拒绝住址、证件、账号、财务等敏感信息，而不仅仅依赖提示词约束。
 *
 * <p>档案分层解锁（记忆星图）：基础 7 项自初识起即可记录（minStage=1，保持既有行为不变），
 * 更私人的偏好随羁绊阶段提升逐步解锁，形成“越熟越懂”的可视化收集感。分层白名单仍是代码级硬约束。
 */
@Slf4j
@Service
public class AgentProfileService {

    /** 档案字段目录项：键、中文标签、解锁所需的最低羁绊阶段。 */
    public record ProfileField(String key, String label, int minStage) {
    }

    /** 档案字段目录（保持顺序，供星图/前端展示）。 */
    public static final List<ProfileField> PROFILE_CATALOG;
    /** 白名单字段键 → 中文标签（由目录派生，兼容既有引用）。 */
    public static final Map<String, String> PROFILE_LABELS;
    private static final Map<String, Integer> MIN_STAGE;
    static {
        List<ProfileField> c = new ArrayList<>();
        // —— 基础层（初识即可记录，minStage=1，与既有行为一致）——
        c.add(new ProfileField("nickname", "昵称", 1));
        c.add(new ProfileField("preferredCall", "喜欢的称呼", 1));
        c.add(new ProfileField("storyType", "喜欢的故事类型", 1));
        c.add(new ProfileField("storyLength", "故事长短偏好", 1));
        c.add(new ProfileField("companionStyle", "陪伴风格", 1));
        c.add(new ProfileField("activeTime", "常用互动时间", 1));
        c.add(new ProfileField("importantDate", "重要日期", 1));
        // —— 熟悉层（stage>=2 解锁）——
        c.add(new ProfileField("favoriteFood", "喜欢的食物", 2));
        c.add(new ProfileField("hobby", "兴趣爱好", 2));
        // —— 老朋友层（stage>=3 解锁）——
        c.add(new ProfileField("comfortTopic", "会被治愈的话题", 3));
        c.add(new ProfileField("recentGoal", "最近的小目标", 3));
        // —— 星球密友层（stage>=4 解锁）——
        c.add(new ProfileField("dream", "心里的愿望", 4));
        PROFILE_CATALOG = List.copyOf(c);

        Map<String, String> labels = new LinkedHashMap<>();
        Map<String, Integer> minStage = new LinkedHashMap<>();
        for (ProfileField f : c) {
            labels.put(f.key(), f.label());
            minStage.put(f.key(), f.minStage());
        }
        PROFILE_LABELS = labels;
        MIN_STAGE = minStage;
    }

    public static final int MAX_VALUE_LENGTH = 500;

    @Resource
    private AgentProfileMapper agentProfileMapper;

    // 仅用于按羁绊阶段做分层解锁校验；@Lazy 避免与运行时状态服务的初始化顺序耦合。
    @Resource
    @Lazy
    private AgentStateService agentStateService;

    /** 是否为已登记的白名单字段键。 */
    public boolean isAllowedKey(String key) {
        return key != null && PROFILE_LABELS.containsKey(key);
    }

    /** 字段解锁所需的最低羁绊阶段，未登记键按 1。 */
    public int minStage(String key) {
        return MIN_STAGE.getOrDefault(key, 1);
    }

    /** 列出某设备+角色的全部档案项。 */
    public List<AgentProfileDO> list(String deviceId, Integer roleId) {
        return agentProfileMapper.selectList(new LambdaQueryWrapper<AgentProfileDO>()
                .eq(AgentProfileDO::getDeviceId, deviceId)
                .eq(AgentProfileDO::getRoleId, roleId)
                .orderByAsc(AgentProfileDO::getFieldKey));
    }

    /** 以 key→value 形式返回档案（仅白名单键）。 */
    public Map<String, String> map(String deviceId, Integer roleId) {
        Map<String, String> result = new LinkedHashMap<>();
        for (AgentProfileDO d : list(deviceId, roleId)) {
            if (isAllowedKey(d.getFieldKey())) {
                result.put(d.getFieldKey(), d.getFieldValue());
            }
        }
        return result;
    }

    /**
     * 记忆星图：目录全集叠加“已解锁 / 已记录 / 当前值”，供 App/管理端可视化收集进度。
     *
     * @param stageLevel 当前羁绊阶段等级（1-4），用于判定解锁
     */
    public List<Map<String, Object>> starMap(String deviceId, Integer roleId, int stageLevel) {
        Map<String, String> filled = map(deviceId, roleId);
        List<Map<String, Object>> stars = new ArrayList<>();
        for (ProfileField f : PROFILE_CATALOG) {
            Map<String, Object> star = new LinkedHashMap<>();
            star.put("key", f.key());
            star.put("label", f.label());
            star.put("minStage", f.minStage());
            star.put("unlocked", stageLevel >= f.minStage());
            star.put("filled", filled.containsKey(f.key()));
            star.put("value", filled.get(f.key()));
            stars.add(star);
        }
        return stars;
    }

    /**
     * upsert 一个档案字段。
     *
     * @throws IllegalArgumentException 键不在白名单、值为空、或该字段尚未随羁绊阶段解锁时拒绝写入。
     */
    public void updateField(Integer userId, String deviceId, Integer roleId, String key, String value) {
        if (!isAllowedKey(key)) {
            throw new IllegalArgumentException("不允许记录的档案字段: " + key);
        }
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("档案值不能为空");
        }
        int need = minStage(key);
        if (need > 1 && currentStageLevel(deviceId, roleId) < need) {
            throw new IllegalArgumentException("这个记忆要等你们更熟悉一点才能记录哦");
        }
        String trimmed = value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) : value;

        AgentProfileDO existing = agentProfileMapper.selectOne(new LambdaQueryWrapper<AgentProfileDO>()
                .eq(AgentProfileDO::getDeviceId, deviceId)
                .eq(AgentProfileDO::getRoleId, roleId)
                .eq(AgentProfileDO::getFieldKey, key)
                .last("limit 1"));
        if (existing != null) {
            existing.setFieldValue(trimmed);
            existing.setUserId(userId);
            agentProfileMapper.updateById(existing);
            return;
        }
        AgentProfileDO row = new AgentProfileDO();
        row.setDeviceId(deviceId);
        row.setRoleId(roleId);
        row.setUserId(userId);
        row.setFieldKey(key);
        row.setFieldValue(trimmed);
        try {
            agentProfileMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发下另一个线程已插入，退化为更新
            AgentProfileDO again = agentProfileMapper.selectOne(new LambdaQueryWrapper<AgentProfileDO>()
                    .eq(AgentProfileDO::getDeviceId, deviceId)
                    .eq(AgentProfileDO::getRoleId, roleId)
                    .eq(AgentProfileDO::getFieldKey, key)
                    .last("limit 1"));
            if (again != null) {
                again.setFieldValue(trimmed);
                again.setUserId(userId);
                agentProfileMapper.updateById(again);
            }
        }
    }

    /** 删除单个档案字段。 */
    public int deleteField(String deviceId, Integer roleId, String key) {
        return agentProfileMapper.delete(new LambdaQueryWrapper<AgentProfileDO>()
                .eq(AgentProfileDO::getDeviceId, deviceId)
                .eq(AgentProfileDO::getRoleId, roleId)
                .eq(AgentProfileDO::getFieldKey, key));
    }

    /** 删除某设备+角色的全部档案（用户"清空档案"）。 */
    public int deleteAll(String deviceId, Integer roleId) {
        return agentProfileMapper.delete(new LambdaQueryWrapper<AgentProfileDO>()
                .eq(AgentProfileDO::getDeviceId, deviceId)
                .eq(AgentProfileDO::getRoleId, roleId));
    }

    private int currentStageLevel(String deviceId, Integer roleId) {
        try {
            AgentStateDO state = agentStateService.getOrCreate(deviceId, roleId);
            int days = state.getCompanionDays() == null ? 0 : state.getCompanionDays();
            return CompanionStage.fromDays(days).level();
        } catch (Exception e) {
            log.warn("读取羁绊阶段失败，按初识处理 deviceId={}, roleId={}", deviceId, roleId, e);
            return 1;
        }
    }
}
