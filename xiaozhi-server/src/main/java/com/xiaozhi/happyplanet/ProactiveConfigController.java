package com.xiaozhi.happyplanet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ProactiveConfigDO;
import com.xiaozhi.happyplanet.service.ProactiveConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待命主动搭话配置接口（控制台）。开关默认关闭；GET 无副作用（未配置时返回默认值预览）。
 */
@Slf4j
@RestController
@RequestMapping("/api/proactive")
@Tag(name = "待命主动搭话", description = "设备待命时主动搭话的开关与策略参数")
public class ProactiveConfigController {

    @Resource
    private ProactiveConfigService proactiveConfigService;

    @GetMapping("/config")
    @SaCheckLogin
    @Operation(summary = "查询主动搭话配置", description = "未配置时返回默认值预览（enabled=0），不创建行")
    public ApiResponse<?> get(@RequestParam String deviceId, @RequestParam Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        ProactiveConfigDO cfg = proactiveConfigService.find(deviceId, roleId);
        if (cfg == null) {
            cfg = new ProactiveConfigDO();
            cfg.setDeviceId(deviceId);
            cfg.setRoleId(roleId);
            cfg.setEnabled(0);
        }
        proactiveConfigService.applyDefaults(cfg);
        return ApiResponse.success(view(cfg));
    }

    @PostMapping("/config")
    @SaCheckLogin
    @Operation(summary = "更新主动搭话配置", description = "仅更新提供的字段；含开关与全部可控变量")
    public ApiResponse<?> update(@RequestBody Map<String, Object> body) {
        String deviceId = asString(body.get("deviceId"));
        Integer roleId = asInteger(body.get("roleId"));
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        try {
            ProactiveConfigDO patch = new ProactiveConfigDO();
            patch.setEnabled(asBoolInt(body.get("enabled")));
            patch.setAllowLlm(asBoolInt(body.get("allowLlm")));
            patch.setDailyLimit(asInteger(body.get("dailyLimit")));
            patch.setCooldownMinutes(asInteger(body.get("cooldownMinutes")));
            patch.setMinIdleSeconds(asInteger(body.get("minIdleSeconds")));
            patch.setActiveStart(asTime(body.get("activeStart")));
            patch.setActiveEnd(asTime(body.get("activeEnd")));
            patch.setQuietStart(asTime(body.get("quietStart")));
            patch.setQuietEnd(asTime(body.get("quietEnd")));
            ProactiveConfigDO saved = proactiveConfigService.update(deviceId, roleId, patch);
            return ApiResponse.success(view(saved));
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @PostMapping("/toggle")
    @SaCheckLogin
    @Operation(summary = "开关主动搭话", description = "仅切换总开关，便于快速开启/关闭")
    public ApiResponse<?> toggle(@RequestBody Map<String, Object> body) {
        String deviceId = asString(body.get("deviceId"));
        Integer roleId = asInteger(body.get("roleId"));
        Integer enabled = asBoolInt(body.get("enabled"));
        if (!StringUtils.hasText(deviceId) || roleId == null || enabled == null) {
            return ApiResponse.badRequest("deviceId / roleId / enabled 必填");
        }
        proactiveConfigService.setEnabled(deviceId, roleId, enabled == 1);
        return ApiResponse.success(Map.of("enabled", enabled));
    }

    private Map<String, Object> view(ProactiveConfigDO cfg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deviceId", cfg.getDeviceId());
        m.put("roleId", cfg.getRoleId());
        m.put("enabled", cfg.getEnabled());
        m.put("allowLlm", cfg.getAllowLlm());
        m.put("dailyLimit", cfg.getDailyLimit());
        m.put("cooldownMinutes", cfg.getCooldownMinutes());
        m.put("minIdleSeconds", cfg.getMinIdleSeconds());
        m.put("activeStart", cfg.getActiveStart() != null ? cfg.getActiveStart().toString() : null);
        m.put("activeEnd", cfg.getActiveEnd() != null ? cfg.getActiveEnd().toString() : null);
        m.put("quietStart", cfg.getQuietStart() != null ? cfg.getQuietStart().toString() : null);
        m.put("quietEnd", cfg.getQuietEnd() != null ? cfg.getQuietEnd().toString() : null);
        return m;
    }

    private String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
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

    /** 接受 boolean / 0-1 / "true"-"false"，归一化为 0/1；无法识别返回 null（不更新）。 */
    private Integer asBoolInt(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0 ? 1 : 0;
        }
        String s = String.valueOf(o).trim().toLowerCase();
        if (s.equals("true") || s.equals("1")) {
            return 1;
        }
        if (s.equals("false") || s.equals("0")) {
            return 0;
        }
        return null;
    }

    /** 解析 "HH:mm" 或 "HH:mm:ss"；非法则抛 IllegalArgumentException。 */
    private LocalTime asTime(Object o) {
        String s = asString(o);
        if (s == null) {
            return null;
        }
        try {
            return LocalTime.parse(s);
        } catch (Exception e) {
            throw new IllegalArgumentException("时间格式应为 HH:mm 或 HH:mm:ss：" + s);
        }
    }
}
