package com.xiaozhi.happyplanet;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.xiaozhi.common.annotation.AuditLog;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.happyplanet.service.GrowthPromptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 成长状态提示词编辑接口（控制台）。按角色（roleId）差异化；roleId 缺省=0 表示全局默认。
 *
 * <p>复用「提示词模板」权限点（同一批运营人员管理），无需单独授权。GET 无副作用：
 * 返回全部槽位的当前生效文案与来源（角色自定义/全局自定义/系统默认）。
 */
@Slf4j
@RestController
@RequestMapping("/api/growth-prompt")
@Tag(name = "成长状态提示词", description = "按角色可编辑的成长状态动态提示词槽位")
public class GrowthPromptController {

    @Resource
    private GrowthPromptService growthPromptService;

    @GetMapping("")
    @SaCheckPermission("system:prompt-template:api:list")
    @Operation(summary = "查询成长提示词槽位", description = "返回该角色全部槽位的生效文案与来源；未覆盖槽位回退默认")
    public ApiResponse<?> list(@RequestParam(required = false) Integer roleId) {
        return ApiResponse.success(growthPromptService.list(normalizeRoleId(roleId)));
    }

    @PutMapping("")
    @SaCheckPermission("system:prompt-template:api:update")
    @AuditLog(module = "成长状态提示词", operation = "保存成长提示词槽位")
    @Operation(summary = "保存成长提示词槽位", description = "新增或更新某角色某槽位的覆盖文案与启用状态")
    public ApiResponse<?> save(@RequestBody Map<String, Object> body) {
        Integer roleId = asInteger(body.get("roleId"));
        String slotKey = asString(body.get("slotKey"));
        String content = asString(body.get("content"));
        Boolean enabled = asBoolean(body.get("enabled"));
        if (!StringUtils.hasText(slotKey)) {
            return ApiResponse.badRequest("slotKey 必填");
        }
        if (!StringUtils.hasText(content)) {
            return ApiResponse.badRequest("内容不能为空；如需关闭该槽位，请用启用开关而非清空内容");
        }
        try {
            growthPromptService.save(normalizeRoleId(roleId), slotKey, content, enabled == null || enabled);
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
        return ApiResponse.success("保存成功");
    }

    @DeleteMapping("")
    @SaCheckPermission("system:prompt-template:api:update")
    @AuditLog(module = "成长状态提示词", operation = "重置成长提示词槽位")
    @Operation(summary = "重置成长提示词槽位", description = "删除该角色该槽位的覆盖，回退到全局或系统默认")
    public ApiResponse<?> reset(@RequestParam(required = false) Integer roleId, @RequestParam String slotKey) {
        if (!StringUtils.hasText(slotKey)) {
            return ApiResponse.badRequest("slotKey 必填");
        }
        growthPromptService.reset(normalizeRoleId(roleId), slotKey);
        return ApiResponse.success("已重置为默认");
    }

    private int normalizeRoleId(Integer roleId) {
        return roleId == null ? GrowthPromptService.GLOBAL_ROLE_ID : roleId;
    }

    private String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isEmpty() ? null : s;
    }

    private Integer asInteger(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean asBoolean(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.equals("true") || s.equals("1")) {
            return true;
        }
        if (s.equals("false") || s.equals("0")) {
            return false;
        }
        return null;
    }
}
