package com.xiaozhi.happyplanet.service;

import java.time.Instant;
import java.time.LocalTime;

/**
 * 待命主动搭话的纯逻辑护栏。<b>无 Spring、无 IO</b>，便于单元测试穷举各时段/频率/退避分支。
 *
 * <p>“主动 ≠ 打扰”：任一护栏不通过即放弃本次主动，设备会自然静默并由不活跃检查关闭会话。
 */
public final class ProactiveGuard {

    private ProactiveGuard() {}

    /** 放弃原因；OK 表示允许本次主动搭话。 */
    public enum Decision {
        OK,
        DISABLED,        // 开关关闭 / 无配置
        IN_QUIET,        // 处于硬静默时段（优先级最高）
        OUTSIDE_ACTIVE,  // 不在活跃时段
        IGNORED_TODAY,   // 当天已被忽略一次，退避
        DAILY_LIMIT,     // 达当日次数上限
        COOLDOWN         // 距上次主动未过冷却
    }

    /**
     * 是否处于静默时段 [quietStart, quietEnd)。支持跨零点（如 22:00–08:00）。
     * quietStart==quietEnd 视为“无静默时段”。
     */
    public static boolean inQuiet(LocalTime now, LocalTime quietStart, LocalTime quietEnd) {
        if (now == null || quietStart == null || quietEnd == null) {
            return false;
        }
        if (quietStart.equals(quietEnd)) {
            return false;
        }
        if (quietStart.isBefore(quietEnd)) {
            return !now.isBefore(quietStart) && now.isBefore(quietEnd);
        }
        // 跨零点
        return !now.isBefore(quietStart) || now.isBefore(quietEnd);
    }

    /**
     * 是否处于活跃时段 [activeStart, activeEnd)。支持跨零点。
     * activeStart==activeEnd 视为“全天活跃”。
     */
    public static boolean inActive(LocalTime now, LocalTime activeStart, LocalTime activeEnd) {
        if (now == null || activeStart == null || activeEnd == null) {
            return true;
        }
        if (activeStart.equals(activeEnd)) {
            return true;
        }
        if (activeStart.isBefore(activeEnd)) {
            return !now.isBefore(activeStart) && now.isBefore(activeEnd);
        }
        return !now.isBefore(activeStart) || now.isBefore(activeEnd);
    }

    /**
     * 综合判定是否可以主动搭话。护栏顺序：开关 → 静默 → 活跃 → 当日退避 → 次数上限 → 冷却。
     *
     * @param minutesSinceLast 距上次主动搭话的分钟数；null 表示从未主动过（不触发冷却）。
     */
    public static Decision evaluate(boolean enabled,
                                    LocalTime now,
                                    LocalTime activeStart, LocalTime activeEnd,
                                    LocalTime quietStart, LocalTime quietEnd,
                                    int dailyLimit, int todayCount, boolean ignoredToday,
                                    int cooldownMinutes, Long minutesSinceLast) {
        if (!enabled) {
            return Decision.DISABLED;
        }
        if (inQuiet(now, quietStart, quietEnd)) {
            return Decision.IN_QUIET;
        }
        if (!inActive(now, activeStart, activeEnd)) {
            return Decision.OUTSIDE_ACTIVE;
        }
        if (ignoredToday) {
            return Decision.IGNORED_TODAY;
        }
        if (todayCount >= dailyLimit) {
            return Decision.DAILY_LIMIT;
        }
        if (minutesSinceLast != null && minutesSinceLast < cooldownMinutes) {
            return Decision.COOLDOWN;
        }
        return Decision.OK;
    }

    /**
     * 启发式判断“主动搭话是否被忽略”：主动播报后，若在宽限期内会话再无有效活动，视为被忽略。
     * 说明：播报本身会刷新 lastActivity，故以 greetingAt + 播报宽限秒 作为分界；此后仍无活动即忽略。
     * 这是无法在无设备环境精确验证的软判定，仅用于“当天退避”，宁可少判忽略、不误伤。
     */
    public static boolean looksIgnored(Instant greetingAt, Instant lastActivity, int speakGraceSeconds) {
        if (greetingAt == null) {
            return false;
        }
        if (lastActivity == null) {
            return true;
        }
        return lastActivity.isBefore(greetingAt.plusSeconds(speakGraceSeconds));
    }
}
