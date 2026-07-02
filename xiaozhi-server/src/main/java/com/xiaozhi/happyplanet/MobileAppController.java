package com.xiaozhi.happyplanet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.xiaozhi.common.model.ChatToken;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.resp.DeviceResp;
import com.xiaozhi.common.model.resp.PageResp;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.device.service.DeviceService;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlayerAgentDO;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ProactiveConfigDO;
import com.xiaozhi.happyplanet.service.AgentProfileService;
import com.xiaozhi.happyplanet.service.AgentRuntimeService;
import com.xiaozhi.happyplanet.service.PlayerAgentService;
import com.xiaozhi.happyplanet.service.ProactiveConfigService;
import com.xiaozhi.happyplanet.service.WeeklyReportService;
import com.xiaozhi.role.service.RoleService;
import com.xiaozhi.server.web.chat.WebChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/app")
@Tag(name = "移动端 App", description = "用户移动端 AI 关联、星球档案与聊天接口")
public class MobileAppController {

    @Resource
    private DeviceService deviceService;
    @Resource
    private RoleService roleService;
    @Resource
    private AgentProfileService agentProfileService;
    @Resource
    private AgentRuntimeService agentRuntimeService;
    @Resource
    private PlayerAgentService playerAgentService;
    @Resource
    private WebChatService webChatService;
    @Resource
    private ProactiveConfigService proactiveConfigService;
    @Resource
    private WeeklyReportService weeklyReportService;

    @GetMapping({"/agents", "/devices"})
    @SaCheckLogin
    @Operation(summary = "我的 AI 列表")
    public ApiResponse<?> agents() {
        Integer userId = StpUtil.getLoginIdAsInt();
        List<Map<String, Object>> list = playerAgentService.listByUser(userId).stream()
                .map(this::agentView)
                .toList();
        PageResp<Map<String, Object>> page = new PageResp<>(list, (long) list.size(), 1, 100);
        return ApiResponse.success(page);
    }

    @GetMapping({"/agents/{deviceId}", "/devices/{deviceId}"})
    @SaCheckLogin
    @Operation(summary = "我的 AI 详情")
    public ApiResponse<?> agent(@PathVariable String deviceId) {
        Integer userId = StpUtil.getLoginIdAsInt();
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(userId, deviceId, null);
        return ApiResponse.success(agentView(link));
    }

    @PostMapping({"/agents/link", "/devices/bind"})
    @SaCheckLogin
    @Operation(summary = "关联我的 AI")
    public ApiResponse<?> linkAgent(@RequestBody Map<String, Object> body) {
        String code = body == null ? null : asString(body.get("code"));
        PlayerAgentDO link = playerAgentService.linkByCode(StpUtil.getLoginIdAsInt(), code);
        return ApiResponse.success(agentView(link));
    }

    @GetMapping("/roles")
    @SaCheckLogin
    @Operation(summary = "我的 AI 角色")
    public ApiResponse<?> roles() {
        Integer userId = StpUtil.getLoginIdAsInt();
        List<Map<String, Object>> roles = playerAgentService.listByUser(userId).stream()
                .map(PlayerAgentDO::getRoleId)
                .filter(Objects::nonNull)
                .distinct()
                .map(roleService::getBO)
                .filter(Objects::nonNull)
                .map(role -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("roleId", role.getRoleId());
                    item.put("roleName", role.getRoleName());
                    item.put("roleDesc", role.getRoleDesc());
                    item.put("avatar", role.getAvatar());
                    item.put("isDefault", role.getIsDefault());
                    return item;
                })
                .toList();
        return ApiResponse.success(roles);
    }

    @GetMapping("/planet")
    @SaCheckLogin
    @Operation(summary = "我的快乐星球档案")
    public ApiResponse<?> planet(@RequestParam String deviceId, @RequestParam Integer roleId) {
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(StpUtil.getLoginIdAsInt(), deviceId, roleId);
        return ApiResponse.success(agentRuntimeService.describe(link.getDeviceId(), link.getRoleId()));
    }

    @PostMapping("/planet/profile")
    @SaCheckLogin
    @Operation(summary = "App 更新星球档案")
    public ApiResponse<?> savePlanetProfile(@RequestBody Map<String, Object> body) {
        String deviceId = body == null ? null : asString(body.get("deviceId"));
        Integer roleId = body == null ? null : asInteger(body.get("roleId"));
        String field = body == null ? null : asString(body.get("field"));
        String value = body == null ? null : asString(body.get("value"));
        if (!StringUtils.hasText(deviceId) || roleId == null
                || !StringUtils.hasText(field) || !StringUtils.hasText(value)) {
            return ApiResponse.badRequest("deviceId/roleId/field/value 必填");
        }

        Integer userId = StpUtil.getLoginIdAsInt();
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(userId, deviceId, roleId);
        try {
            agentProfileService.updateField(userId, link.getDeviceId(), link.getRoleId(), field, value);
            return ApiResponse.success("已更新");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/planet/profile")
    @SaCheckLogin
    @Operation(summary = "App 删除星球档案项")
    public ApiResponse<?> deletePlanetProfile(@RequestParam String deviceId,
                                              @RequestParam Integer roleId,
                                              @RequestParam String field) {
        if (!StringUtils.hasText(deviceId) || roleId == null || !StringUtils.hasText(field)) {
            return ApiResponse.badRequest("deviceId/roleId/field 必填");
        }

        PlayerAgentDO link = assertAgentLinkedByCurrentUser(StpUtil.getLoginIdAsInt(), deviceId, roleId);
        int removed = agentProfileService.deleteField(link.getDeviceId(), link.getRoleId(), field);
        return ApiResponse.success(Map.of("removed", removed));
    }

    @GetMapping("/report")
    @SaCheckLogin
    @Operation(summary = "本周星球周报")
    public ApiResponse<?> report(@RequestParam String deviceId, @RequestParam Integer roleId) {
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(StpUtil.getLoginIdAsInt(), deviceId, roleId);
        return ApiResponse.success(weeklyReportService.rolling(link.getDeviceId(), link.getRoleId()));
    }

    @GetMapping("/reports")
    @SaCheckLogin
    @Operation(summary = "历史星球周报")
    public ApiResponse<?> reports(@RequestParam String deviceId, @RequestParam Integer roleId) {
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(StpUtil.getLoginIdAsInt(), deviceId, roleId);
        return ApiResponse.success(weeklyReportService.history(link.getDeviceId(), link.getRoleId(), 12));
    }

    @PostMapping("/chat/open")
    @SaCheckLogin
    @Operation(summary = "打开 App 聊天会话")
    public Map<String, String> openChat(@RequestParam String deviceId,
                                        @RequestParam Integer roleId,
                                        @RequestParam(required = false) String sessionId) {
        Integer userId = StpUtil.getLoginIdAsInt();
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(userId, deviceId, roleId);
        return Map.of("sessionId", webChatService.openAppSession(userId, link.getRoleId(), link.getDeviceId(), sessionId));
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @SaCheckLogin
    @Operation(summary = "App 流式聊天")
    public Flux<ServerSentEvent<ChatToken>> streamChat(@RequestParam String sessionId, @RequestParam String text) {
        Integer userId = StpUtil.getLoginIdAsInt();
        if (!webChatService.isActiveSessionOwnedByUser(sessionId, userId)) {
            return Flux.error(new IllegalArgumentException("会话不存在或无权访问"));
        }
        return webChatService.chatStream(sessionId, text)
                .map(token -> ServerSentEvent.builder(token).build());
    }

    @PostMapping("/chat/close")
    @SaCheckLogin
    @Operation(summary = "关闭 App 聊天会话")
    public Map<String, String> closeChat(@RequestParam String sessionId) {
        Integer userId = StpUtil.getLoginIdAsInt();
        if (webChatService.isActiveSessionOwnedByUser(sessionId, userId)) {
            webChatService.closeSession(sessionId);
        }
        return Map.of("status", "closed");
    }

    @GetMapping("/proactive")
    @SaCheckLogin
    @Operation(summary = "查询主动搭话开关")
    public ApiResponse<?> proactiveConfig(@RequestParam String deviceId, @RequestParam Integer roleId) {
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(StpUtil.getLoginIdAsInt(), deviceId, roleId);
        ProactiveConfigDO cfg = proactiveConfigService.find(link.getDeviceId(), link.getRoleId());
        if (cfg == null) {
            cfg = new ProactiveConfigDO();
            cfg.setDeviceId(link.getDeviceId());
            cfg.setRoleId(link.getRoleId());
            cfg.setEnabled(0);
        }
        proactiveConfigService.applyDefaults(cfg);
        return ApiResponse.success(proactiveView(cfg));
    }

    @PostMapping("/proactive/toggle")
    @SaCheckLogin
    @Operation(summary = "App 开关主动搭话")
    public ApiResponse<?> proactiveToggle(@RequestBody Map<String, Object> body) {
        String deviceId = body == null ? null : asString(body.get("deviceId"));
        Integer roleId = body == null ? null : asInteger(body.get("roleId"));
        Object enabledRaw = body == null ? null : body.get("enabled");
        if (!StringUtils.hasText(deviceId) || roleId == null || enabledRaw == null) {
            return ApiResponse.badRequest("deviceId/roleId/enabled 必填");
        }
        PlayerAgentDO link = assertAgentLinkedByCurrentUser(StpUtil.getLoginIdAsInt(), deviceId, roleId);
        boolean enabled = asBool(enabledRaw);
        proactiveConfigService.setEnabled(link.getDeviceId(), link.getRoleId(), enabled);
        return ApiResponse.success(Map.of("enabled", enabled ? 1 : 0));
    }

    private Map<String, Object> proactiveView(ProactiveConfigDO cfg) {
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

    private boolean asBool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = String.valueOf(o).trim().toLowerCase();
        return s.equals("true") || s.equals("1") || s.equals("on") || s.equals("开") || s.equals("开启");
    }

    private PlayerAgentDO assertAgentLinkedByCurrentUser(Integer userId, String deviceId, Integer roleId) {
        if (!StringUtils.hasText(deviceId)) {
            throw new IllegalArgumentException("deviceId 不能为空");
        }
        PlayerAgentDO link = roleId == null
                ? playerAgentService.getLinkedByDevice(userId, deviceId)
                : playerAgentService.getLinked(userId, deviceId, roleId);
        if (link == null) {
            throw new IllegalArgumentException("AI 未关联到当前玩家账号");
        }
        return link;
    }

    private Map<String, Object> agentView(PlayerAgentDO link) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("agentLinkId", link.getId());
        item.put("playerUserId", link.getUserId());
        item.put("deviceId", link.getDeviceId());
        item.put("roleId", link.getRoleId());
        item.put("relationState", link.getState());
        item.put("linkedAt", link.getCreateTime());

        DeviceResp device = deviceService.get(link.getDeviceId());
        if (device != null) {
            item.put("sessionId", device.getSessionId());
            item.put("deviceName", device.getDeviceName());
            item.put("state", device.getState());
            item.put("totalMessage", device.getTotalMessage());
            item.put("wifiName", device.getWifiName());
            item.put("ip", device.getIp());
            item.put("chipModelName", device.getChipModelName());
            item.put("type", device.getType());
            item.put("version", device.getVersion());
            item.put("location", device.getLocation());
            item.put("createTime", device.getCreateTime());
            item.put("updateTime", device.getUpdateTime());
        }

        RoleBO role = roleService.getBO(link.getRoleId());
        item.put("roleName", role != null ? role.getRoleName() : null);
        item.put("roleDesc", role != null ? role.getRoleDesc() : null);
        item.put("avatar", role != null ? role.getAvatar() : null);
        return item;
    }

    private String asString(Object o) {
        if (o == null) {
            return null;
        }
        String value = String.valueOf(o).trim();
        return value.isEmpty() ? null : value;
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
}
