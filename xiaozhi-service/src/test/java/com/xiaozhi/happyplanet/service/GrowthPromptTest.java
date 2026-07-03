package com.xiaozhi.happyplanet.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 成长状态提示词纯逻辑测试：变量替换契约与槽位目录完整性。
 */
class GrowthPromptTest {

    // ---------- substitute：{{var}} 替换 ----------

    @Test
    void substitute_replacesKnownVars() {
        Map<String, String> vars = new HashMap<>();
        vars.put("days", "12");
        vars.put("stageLabel", "老朋友");
        String out = GrowthPromptService.substitute("第 {{days}} 天，{{stageLabel}}", vars);
        assertThat(out).isEqualTo("第 12 天，老朋友");
    }

    @Test
    void substitute_missingVarBecomesEmpty() {
        Map<String, String> vars = new HashMap<>();
        vars.put("energy", "80");
        // {{taskText}} 未提供 → 替换为空串
        String out = GrowthPromptService.substitute("能量{{energy}}，任务：{{taskText}}。", vars);
        assertThat(out).isEqualTo("能量80，任务：。");
    }

    @Test
    void substitute_nullVarValueBecomesEmpty() {
        Map<String, String> vars = new HashMap<>();
        vars.put("profileText", null);
        assertThat(GrowthPromptService.substitute("记得：{{profileText}}", vars)).isEqualTo("记得：");
    }

    @Test
    void substitute_handlesNullAndEmptyTemplate() {
        assertThat(GrowthPromptService.substitute(null, Map.of("a", "b"))).isEmpty();
        assertThat(GrowthPromptService.substitute("", Map.of("a", "b"))).isEmpty();
    }

    @Test
    void substitute_noPlaceholderReturnsAsIs() {
        assertThat(GrowthPromptService.substitute("没有占位符", Map.of("a", "b"))).isEqualTo("没有占位符");
        assertThat(GrowthPromptService.substitute("原样返回", null)).isEqualTo("原样返回");
    }

    // ---------- 槽位目录完整性 ----------

    @Test
    void catalog_everySlotHasKeyAndDefault() {
        for (GrowthPromptSlot slot : GrowthPromptSlot.values()) {
            assertThat(slot.key()).as("slot key").isNotBlank();
            assertThat(slot.def()).as("default of %s", slot.key()).isNotBlank();
            assertThat(slot.label()).as("label of %s", slot.key()).isNotBlank();
            assertThat(slot.category()).as("category of %s", slot.key()).isNotNull();
            assertThat(slot.vars()).as("vars of %s", slot.key()).isNotNull();
        }
    }

    @Test
    void catalog_keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (GrowthPromptSlot slot : GrowthPromptSlot.values()) {
            assertThat(keys.add(slot.key())).as("duplicate key %s", slot.key()).isTrue();
        }
    }

    @Test
    void catalog_byKeyResolvesAndRejectsUnknown() {
        assertThat(GrowthPromptSlot.byKey("stage.oldfriend")).isEqualTo(GrowthPromptSlot.STAGE_OLDFRIEND);
        assertThat(GrowthPromptSlot.byKey("time.night")).isEqualTo(GrowthPromptSlot.TIME_NIGHT);
        assertThat(GrowthPromptSlot.byKey("nope.unknown")).isNull();
        assertThat(GrowthPromptSlot.byKey(null)).isNull();
    }

    @Test
    void catalog_declaredVarsActuallyAppearInDefault() {
        // 声明的变量应真的出现在默认文案里，避免文档与实现漂移。
        for (GrowthPromptSlot slot : GrowthPromptSlot.values()) {
            for (String v : slot.vars()) {
                assertThat(slot.def())
                        .as("slot %s declares var %s but default text omits it", slot.key(), v)
                        .contains("{{" + v + "}}");
            }
        }
    }
}
