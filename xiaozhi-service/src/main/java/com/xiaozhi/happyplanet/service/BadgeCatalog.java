package com.xiaozhi.happyplanet.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 星球徽章目录（代码侧唯一真相源）。徽章 = 连击 / 阶段 / 纪念日的可视化成就。
 *
 * <p>徽章键（badgeKey）由此目录约束，未登记的键不予授予/展示；图标用 emoji，前端直接渲染，无需素材管线。
 */
public final class BadgeCatalog {

    /** 连击阈值 → 徽章键（用于连续完成任务的达成判定）。 */
    public static final int[] STREAK_THRESHOLDS = {3, 7, 21};
    /** 陪伴纪念里程碑天数（同时驱动纪念仪式与纪念徽章）。 */
    public static final int[] ANNIVERSARY_MILESTONES = {7, 30, 100, 365};

    public record Badge(String key, String label, String desc, String icon, String category) {
    }

    private static final Map<String, Badge> BADGES;
    static {
        Map<String, Badge> m = new LinkedHashMap<>();
        // 连击类
        put(m, "streak_3", "星球坚持者", "连续完成星球任务 3 天", "🔥", "streak");
        put(m, "streak_7", "一周之约", "连续完成星球任务 7 天", "🏅", "streak");
        put(m, "streak_21", "习惯养成者", "连续完成星球任务 21 天", "💎", "streak");
        // 阶段类
        put(m, "stage_familiar", "熟悉的伙伴", "陪伴进入「熟悉」阶段", "🤝", "stage");
        put(m, "stage_oldfriend", "老朋友", "陪伴进入「老朋友」阶段", "🧡", "stage");
        put(m, "stage_soulmate", "星球密友", "陪伴进入「星球密友」阶段", "🌟", "stage");
        // 纪念日类
        put(m, "day_7", "相伴一周", "累计陪伴满 7 天", "🗓️", "anniversary");
        put(m, "day_30", "相伴一月", "累计陪伴满 30 天", "🌙", "anniversary");
        put(m, "day_100", "百日同行", "累计陪伴满 100 天", "💯", "anniversary");
        put(m, "day_365", "四季相伴", "累计陪伴满 365 天", "🎂", "anniversary");
        // 主题任务链类（完成整条主题周授予，键 = "chain_" + chainKey）
        put(m, "chain_observe_week", "地球观察家", "完成一整周「地球观察周」主题任务", "🔭", "chain");
        BADGES = m;
    }

    private BadgeCatalog() {
    }

    private static void put(Map<String, Badge> m, String key, String label, String desc, String icon, String category) {
        m.put(key, new Badge(key, label, desc, icon, category));
    }

    public static boolean isKnown(String key) {
        return key != null && BADGES.containsKey(key);
    }

    public static Badge get(String key) {
        return BADGES.get(key);
    }

    /** 目录全集（保持登记顺序），供徽章墙展示“已得/未得”。 */
    public static List<Badge> all() {
        return new ArrayList<>(BADGES.values());
    }

    /** 达到给定连击天数时应持有的连击徽章键（含所有已跨过的阈值）。 */
    public static List<String> streakBadgesFor(int streakDays) {
        List<String> keys = new ArrayList<>();
        for (int t : STREAK_THRESHOLDS) {
            if (streakDays >= t) {
                keys.add("streak_" + t);
            }
        }
        return keys;
    }

    /** 纪念里程碑天数 → 徽章键，例如 30 → day_30。 */
    public static String anniversaryBadge(int milestone) {
        return "day_" + milestone;
    }

    /** 主题链键 → 主题勋章键，例如 observe_week → chain_observe_week。未登记的链返回 null（不授勋）。 */
    public static String chainBadge(String chainKey) {
        if (chainKey == null) {
            return null;
        }
        String key = "chain_" + chainKey;
        return isKnown(key) ? key : null;
    }
}
