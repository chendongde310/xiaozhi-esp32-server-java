package com.xiaozhi.happyplanet.service;

import com.xiaozhi.happyplanet.service.ProactiveGuard.Decision;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 待命主动搭话护栏纯逻辑测试：穷举时段/频率/冷却/退避各分支。
 */
class ProactiveGuardTest {

    private static final LocalTime ACTIVE_START = LocalTime.of(9, 0);
    private static final LocalTime ACTIVE_END = LocalTime.of(21, 0);
    private static final LocalTime QUIET_START = LocalTime.of(22, 0);
    private static final LocalTime QUIET_END = LocalTime.of(8, 0);

    // ---------- inQuiet：跨零点静默 22:00–08:00 ----------

    @Test
    void quiet_crossMidnight() {
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(23, 0), QUIET_START, QUIET_END)).isTrue();
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(7, 0), QUIET_START, QUIET_END)).isTrue();
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(22, 0), QUIET_START, QUIET_END)).isTrue();  // 含起点
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(8, 0), QUIET_START, QUIET_END)).isFalse();  // 不含终点
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(12, 0), QUIET_START, QUIET_END)).isFalse();
    }

    @Test
    void quiet_sameDayWindow() {
        LocalTime s = LocalTime.of(13, 0), e = LocalTime.of(14, 0);
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(13, 30), s, e)).isTrue();
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(12, 59), s, e)).isFalse();
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(14, 0), s, e)).isFalse();
    }

    @Test
    void quiet_equalStartEnd_isNone() {
        LocalTime t = LocalTime.of(10, 0);
        assertThat(ProactiveGuard.inQuiet(LocalTime.of(3, 0), t, t)).isFalse();
    }

    // ---------- inActive：正常/跨零点/全天 ----------

    @Test
    void active_normalWindow() {
        assertThat(ProactiveGuard.inActive(LocalTime.of(10, 0), ACTIVE_START, ACTIVE_END)).isTrue();
        assertThat(ProactiveGuard.inActive(LocalTime.of(9, 0), ACTIVE_START, ACTIVE_END)).isTrue();   // 含起点
        assertThat(ProactiveGuard.inActive(LocalTime.of(21, 0), ACTIVE_START, ACTIVE_END)).isFalse(); // 不含终点
        assertThat(ProactiveGuard.inActive(LocalTime.of(8, 59), ACTIVE_START, ACTIVE_END)).isFalse();
    }

    @Test
    void active_equalStartEnd_isWholeDay() {
        LocalTime t = LocalTime.of(0, 0);
        assertThat(ProactiveGuard.inActive(LocalTime.of(3, 0), t, t)).isTrue();
        assertThat(ProactiveGuard.inActive(LocalTime.of(23, 0), t, t)).isTrue();
    }

    @Test
    void active_crossMidnight() {
        LocalTime s = LocalTime.of(20, 0), e = LocalTime.of(2, 0);
        assertThat(ProactiveGuard.inActive(LocalTime.of(23, 0), s, e)).isTrue();
        assertThat(ProactiveGuard.inActive(LocalTime.of(1, 0), s, e)).isTrue();
        assertThat(ProactiveGuard.inActive(LocalTime.of(12, 0), s, e)).isFalse();
    }

    // ---------- evaluate：护栏优先级与各放弃原因 ----------

    private Decision eval(boolean enabled, LocalTime now, int dailyLimit, int todayCount,
                          boolean ignored, int cooldown, Long minsSinceLast) {
        return ProactiveGuard.evaluate(enabled, now, ACTIVE_START, ACTIVE_END, QUIET_START, QUIET_END,
                dailyLimit, todayCount, ignored, cooldown, minsSinceLast);
    }

    @Test
    void evaluate_disabled() {
        assertThat(eval(false, LocalTime.of(10, 0), 3, 0, false, 90, null)).isEqualTo(Decision.DISABLED);
    }

    @Test
    void evaluate_quietTakesPrecedenceOverActive() {
        // 全天活跃 + 跨零点静默；23:00 同时“在活跃”“在静默”，应判 IN_QUIET
        Decision d = ProactiveGuard.evaluate(true, LocalTime.of(23, 0),
                LocalTime.of(0, 0), LocalTime.of(0, 0),   // 全天活跃
                QUIET_START, QUIET_END,
                3, 0, false, 90, null);
        assertThat(d).isEqualTo(Decision.IN_QUIET);
    }

    @Test
    void evaluate_outsideActive() {
        // 08:30：不在静默(08:00 结束)，也不在活跃(09:00 开始) → OUTSIDE_ACTIVE
        assertThat(eval(true, LocalTime.of(8, 30), 3, 0, false, 90, null)).isEqualTo(Decision.OUTSIDE_ACTIVE);
    }

    @Test
    void evaluate_ignoredToday() {
        assertThat(eval(true, LocalTime.of(10, 0), 3, 0, true, 90, null)).isEqualTo(Decision.IGNORED_TODAY);
    }

    @Test
    void evaluate_dailyLimitReached() {
        assertThat(eval(true, LocalTime.of(10, 0), 3, 3, false, 90, null)).isEqualTo(Decision.DAILY_LIMIT);
        assertThat(eval(true, LocalTime.of(10, 0), 3, 4, false, 90, null)).isEqualTo(Decision.DAILY_LIMIT);
    }

    @Test
    void evaluate_cooldown() {
        assertThat(eval(true, LocalTime.of(10, 0), 3, 1, false, 90, 30L)).isEqualTo(Decision.COOLDOWN);
    }

    @Test
    void evaluate_ok_withinAllLimits() {
        assertThat(eval(true, LocalTime.of(10, 0), 3, 0, false, 90, null)).isEqualTo(Decision.OK);
        // 冷却刚好到（90>=90 不再冷却）
        assertThat(eval(true, LocalTime.of(10, 0), 3, 1, false, 90, 90L)).isEqualTo(Decision.OK);
    }

    // ---------- looksIgnored 启发式 ----------

    @Test
    void looksIgnored_behaviour() {
        Instant t0 = Instant.parse("2026-07-03T10:00:00Z");
        assertThat(ProactiveGuard.looksIgnored(t0, null, 15)).isTrue();                       // 无活动
        assertThat(ProactiveGuard.looksIgnored(t0, t0.plusSeconds(5), 15)).isTrue();          // 仅自身播报刷新
        assertThat(ProactiveGuard.looksIgnored(t0, t0.plusSeconds(30), 15)).isFalse();        // 播报后有回应
        assertThat(ProactiveGuard.looksIgnored(null, t0, 15)).isFalse();                      // 无基准，不误判
    }
}
