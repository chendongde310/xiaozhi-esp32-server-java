package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.GrowthPromptDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.GrowthPromptMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成长状态提示词槽位读取与渲染服务。
 *
 * <p>分层覆盖：角色自定义行(roleId&gt;0) → 全局自定义行(roleId=0) → 代码内置默认（{@link GrowthPromptSlot#def()}）。
 * 运营在管理端可按角色逐条编辑；任何数据库异常都会回退到代码默认，绝不阻断对话。
 *
 * <p>渲染侧（每轮对话）用 {@link #renderer(Integer)} 一次性载入该角色与全局的覆盖行（共两次查询），
 * 之后在内存中解析各槽位，避免逐槽位查库。
 */
@Slf4j
@Service
public class GrowthPromptService {

    /** 全局默认覆盖所属的 roleId 哨兵值。 */
    public static final int GLOBAL_ROLE_ID = 0;

    @Resource
    private GrowthPromptMapper growthPromptMapper;

    // ==================== 渲染侧（对话每轮调用） ====================

    /**
     * 为某角色构建一次性渲染器：预载该角色与全局的覆盖行。
     * 载入失败（DB 异常）时返回仅含代码默认的渲染器，保证对话不中断。
     */
    public Renderer renderer(Integer roleId) {
        Map<String, GrowthPromptDO> roleMap = load(roleId);
        Map<String, GrowthPromptDO> globalMap =
                (roleId != null && roleId != GLOBAL_ROLE_ID) ? load(GLOBAL_ROLE_ID) : roleMap;
        return new Renderer(roleMap, globalMap);
    }

    private Map<String, GrowthPromptDO> load(Integer roleId) {
        Map<String, GrowthPromptDO> map = new LinkedHashMap<>();
        if (roleId == null) {
            return map;
        }
        try {
            List<GrowthPromptDO> rows = growthPromptMapper.selectList(
                    new LambdaQueryWrapper<GrowthPromptDO>().eq(GrowthPromptDO::getRoleId, roleId));
            for (GrowthPromptDO row : rows) {
                map.put(row.getSlotKey(), row);
            }
        } catch (Exception e) {
            log.warn("载入成长提示词覆盖失败 roleId={}，回退代码默认", roleId, e);
        }
        return map;
    }

    /**
     * 一次性渲染器：解析单个槽位并做变量替换。
     * 解析规则：角色行存在→用之（enabled=0 视为该角色关闭此槽位，返回空）；否则全局行→用之（同上）；否则代码默认。
     */
    public final class Renderer {
        private final Map<String, GrowthPromptDO> roleMap;
        private final Map<String, GrowthPromptDO> globalMap;

        private Renderer(Map<String, GrowthPromptDO> roleMap, Map<String, GrowthPromptDO> globalMap) {
            this.roleMap = roleMap;
            this.globalMap = globalMap;
        }

        /** 渲染槽位为最终文案；被关闭或空内容返回 ""。 */
        public String render(GrowthPromptSlot slot, Map<String, String> vars) {
            if (slot == null) {
                return "";
            }
            GrowthPromptDO row = roleMap.get(slot.key());
            if (row == null) {
                row = globalMap.get(slot.key());
            }
            String content;
            if (row != null) {
                if (row.getEnabled() != null && row.getEnabled() == 0) {
                    return ""; // 显式关闭此槽位
                }
                content = row.getContent();
            } else {
                content = slot.def();
            }
            return substitute(content, vars);
        }
    }

    /** 便捷方法：单槽位渲染（非每轮热路径场景使用）。 */
    public String render(Integer roleId, GrowthPromptSlot slot, Map<String, String> vars) {
        return renderer(roleId).render(slot, vars);
    }

    /**
     * 用 {@code {{key}}} 语法替换变量。已提供的变量替换为其值；未提供或值为 null 的占位符统一清成空串
     * （运营可能在任意槽位里写入任意变量，必须防止未解析的 {{xxx}} 被原样注入 LLM）。
     */
    static String substitute(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (template.indexOf("{{") < 0) {
            return template;
        }
        String out = template;
        if (vars != null) {
            for (Map.Entry<String, String> e : vars.entrySet()) {
                String v = e.getValue() == null ? "" : e.getValue();
                out = out.replace("{{" + e.getKey() + "}}", v);
            }
        }
        // 清除任何仍未解析的占位符 {{...}}，避免原样朗读。
        return out.replaceAll("\\{\\{[^{}]*}}", "");
    }

    // ==================== 管理侧（控制台编辑） ====================

    /**
     * 管理端槽位视图：目录元数据 + 该角色当前生效的文案与来源。
     *
     * @param source role=角色自定义 / global=全局自定义 / default=系统默认
     */
    public record SlotView(String slotKey, String category, String categoryLabel, String label,
                           String desc, List<String> vars, String content, boolean enabled, String source) {
    }

    /** 列出某角色的全部槽位（含未覆盖的，回退默认），供管理端编辑。 */
    public List<SlotView> list(Integer roleId) {
        Map<String, GrowthPromptDO> roleMap = load(roleId);
        Map<String, GrowthPromptDO> globalMap =
                (roleId != null && roleId != GLOBAL_ROLE_ID) ? load(GLOBAL_ROLE_ID) : roleMap;

        List<SlotView> out = new ArrayList<>(GrowthPromptSlot.values().length);
        for (GrowthPromptSlot slot : GrowthPromptSlot.values()) {
            GrowthPromptDO roleRow = roleMap.get(slot.key());
            GrowthPromptDO globalRow = globalMap.get(slot.key());
            String content;
            boolean enabled;
            String source;
            if (roleRow != null && roleId != null && roleId != GLOBAL_ROLE_ID) {
                content = roleRow.getContent();
                enabled = roleRow.getEnabled() == null || roleRow.getEnabled() == 1;
                source = "role";
            } else if (globalRow != null) {
                content = globalRow.getContent();
                enabled = globalRow.getEnabled() == null || globalRow.getEnabled() == 1;
                source = "global";
            } else {
                content = slot.def();
                enabled = true;
                source = "default";
            }
            out.add(new SlotView(slot.key(), slot.category().name(), slot.category().label(),
                    slot.label(), slot.desc(), slot.vars(), content, enabled, source));
        }
        return out;
    }

    /** 保存（新增或更新）某角色某槽位的覆盖文案。slotKey 必须属于目录。 */
    public void save(Integer roleId, String slotKey, String content, boolean enabled) {
        GrowthPromptSlot slot = GrowthPromptSlot.byKey(slotKey);
        if (slot == null) {
            throw new IllegalArgumentException("未知的成长提示词槽位: " + slotKey);
        }
        int rid = roleId == null ? GLOBAL_ROLE_ID : roleId;
        GrowthPromptDO existing = growthPromptMapper.selectOne(
                new LambdaQueryWrapper<GrowthPromptDO>()
                        .eq(GrowthPromptDO::getRoleId, rid)
                        .eq(GrowthPromptDO::getSlotKey, slotKey)
                        .last("limit 1"));
        if (existing != null) {
            existing.setContent(content);
            existing.setEnabled(enabled ? 1 : 0);
            growthPromptMapper.updateById(existing);
            return;
        }
        GrowthPromptDO row = new GrowthPromptDO();
        row.setRoleId(rid);
        row.setSlotKey(slotKey);
        row.setContent(content);
        row.setEnabled(enabled ? 1 : 0);
        try {
            growthPromptMapper.insert(row);
        } catch (DuplicateKeyException e) {
            GrowthPromptDO now = growthPromptMapper.selectOne(
                    new LambdaQueryWrapper<GrowthPromptDO>()
                            .eq(GrowthPromptDO::getRoleId, rid)
                            .eq(GrowthPromptDO::getSlotKey, slotKey)
                            .last("limit 1"));
            if (now != null) {
                now.setContent(content);
                now.setEnabled(enabled ? 1 : 0);
                growthPromptMapper.updateById(now);
            }
        }
    }

    /** 重置：删除该角色该槽位的覆盖行，回退到下一层（全局或代码默认）。 */
    public void reset(Integer roleId, String slotKey) {
        int rid = roleId == null ? GLOBAL_ROLE_ID : roleId;
        growthPromptMapper.delete(new LambdaQueryWrapper<GrowthPromptDO>()
                .eq(GrowthPromptDO::getRoleId, rid)
                .eq(GrowthPromptDO::getSlotKey, slotKey));
    }
}
