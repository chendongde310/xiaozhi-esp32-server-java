package com.xiaozhi.happyplanet.service;

/**
 * 羁绊阶段（关系等级）——由累计陪伴天数实时推导。
 *
 * <p>阶段本身不落库；{@code sys_agent_state.stage} 只记录“最近一次已庆祝到的阶段”，用于保证升级仪式只播报一次。
 * 升级仪式台词键为 {@code stage_up_<key>}（初识为默认起点，无升级仪式）。
 */
public enum CompanionStage {

    ACQUAINTED(1, "初识", "acquainted", 0),
    FAMILIAR(2, "熟悉", "familiar", 3),
    OLD_FRIEND(3, "老朋友", "oldfriend", 10),
    SOULMATE(4, "星球密友", "soulmate", 30);

    private final int level;
    private final String label;
    private final String key;
    private final int minDays;

    CompanionStage(int level, String label, String key, int minDays) {
        this.level = level;
        this.label = label;
        this.key = key;
        this.minDays = minDays;
    }

    public int level() {
        return level;
    }

    public String label() {
        return label;
    }

    public String key() {
        return key;
    }

    public int minDays() {
        return minDays;
    }

    /** 该阶段的升级仪式台词场景键；初识无升级仪式，返回 null。 */
    public String stageUpSceneKey() {
        return this == ACQUAINTED ? null : "stage_up_" + key;
    }

    /** 该阶段对应的徽章键；初识无徽章，返回 null。 */
    public String badgeKey() {
        return this == ACQUAINTED ? null : "stage_" + key;
    }

    /** 由陪伴天数推导当前阶段（取 minDays <= days 的最高阶段）。 */
    public static CompanionStage fromDays(int days) {
        CompanionStage result = ACQUAINTED;
        for (CompanionStage s : values()) {
            if (days >= s.minDays) {
                result = s;
            }
        }
        return result;
    }

    /** 由等级取阶段，越界回退到初识。 */
    public static CompanionStage fromLevel(Integer level) {
        if (level != null) {
            for (CompanionStage s : values()) {
                if (s.level == level) {
                    return s;
                }
            }
        }
        return ACQUAINTED;
    }

    /** 下一阶段；已是最高阶段返回自身。 */
    public CompanionStage next() {
        CompanionStage[] all = values();
        int idx = ordinal();
        return idx + 1 < all.length ? all[idx + 1] : this;
    }

    /**
     * 当前阶段内的进度百分比 0-100：从本阶段 minDays 到下一阶段 minDays 的线性推进。
     * 已是最高阶段恒为 100。
     */
    public int progressPercent(int days) {
        CompanionStage nx = next();
        if (nx == this) {
            return 100;
        }
        int span = nx.minDays - this.minDays;
        if (span <= 0) {
            return 100;
        }
        int done = Math.max(0, days - this.minDays);
        return Math.max(0, Math.min(100, (int) Math.round(done * 100.0 / span)));
    }
}
