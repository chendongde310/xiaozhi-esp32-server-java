package com.xiaozhi.happyplanet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.device.service.DeviceService;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.AgentStateDO;
import com.xiaozhi.happyplanet.service.AgentProfileService;
import com.xiaozhi.happyplanet.service.AgentRuntimeService;
import com.xiaozhi.happyplanet.service.AgentStateService;
import com.xiaozhi.happyplanet.service.DemoSeedService;
import com.xiaozhi.happyplanet.service.PlayerAgentService;
import com.xiaozhi.happyplanet.service.WeeklyReportService;
import com.xiaozhi.role.service.RoleService;
import com.xiaozhi.server.web.BaseController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 快乐星球档案管理接口。对齐甲方 Demo 文档第八节：档案对用户透明、可查看、可修改、可删除。
 */
@Slf4j
@RestController
@RequestMapping("/api/happyplanet")
@Tag(name = "快乐星球", description = "快乐星球档案（长期记忆）与运行状态")
public class HappyPlanetController extends BaseController {

    @Resource
    private AgentRuntimeService agentRuntimeService;
    @Resource
    private AgentStateService agentStateService;
    @Resource
    private AgentProfileService agentProfileService;
    @Resource
    private PlayerAgentService playerAgentService;
    @Resource
    private WeeklyReportService weeklyReportService;
    @Resource
    private DemoSeedService demoSeedService;
    @Resource
    private DeviceService deviceService;
    @Resource
    private RoleService roleService;

    /** 列出全部智能体（设备×角色）状态，供页面选择器。 */
    @GetMapping("/agents")
    @SaCheckLogin
    @Operation(summary = "智能体列表", description = "返回全部设备×角色的快乐星球运行状态")
    public ApiResponse<?> agents() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (AgentStateDO state : agentStateService.listAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", state.getDeviceId());
            item.put("roleId", state.getRoleId());
            item.put("energy", state.getEnergy());
            item.put("companionDays", state.getCompanionDays());
            item.put("currentChannel", state.getCurrentChannel());
            item.put("currentChannelLabel", agentRuntimeService.channelLabel(state.getCurrentChannel()));
            try {
                DeviceBO device = deviceService.getBO(state.getDeviceId());
                item.put("deviceName", device != null ? device.getDeviceName() : state.getDeviceId());
            } catch (Exception e) {
                item.put("deviceName", state.getDeviceId());
            }
            try {
                RoleBO role = roleService.getBO(state.getRoleId());
                item.put("roleName", role != null ? role.getRoleName() : null);
            } catch (Exception e) {
                item.put("roleName", null);
            }
            list.add(item);
        }
        return ApiResponse.success(list);
    }

    /** 查询某设备×角色的档案 + 运行状态组合视图。 */
    @GetMapping("/profile")
    @SaCheckLogin
    @Operation(summary = "查询快乐星球档案", description = "返回档案字段、快乐能量、陪伴天数、当前频道与今日任务")
    public ApiResponse<?> profile(@RequestParam String deviceId, @RequestParam Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        return ApiResponse.success(agentRuntimeService.describe(deviceId, roleId));
    }

    /** 一键填充演示数据：为指定智能体灌入阶段/连击/徽章/能量曲线/档案，便于演示。 */
    @PostMapping("/demo-seed")
    @SaCheckLogin
    @Operation(summary = "填充演示数据", description = "为指定智能体一键灌入成长演示数据（羁绊阶段/连击/徽章/能量曲线/记忆星图），幂等")
    public ApiResponse<?> demoSeed(@RequestParam String deviceId, @RequestParam Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        demoSeedService.seed(deviceId, roleId);
        try {
            weeklyReportService.generateForLastWeek(deviceId, roleId);
        } catch (Exception e) {
            log.warn("演示周报归档失败 deviceId={}, roleId={}", deviceId, roleId, e);
        }
        return ApiResponse.success(agentRuntimeService.describe(deviceId, roleId));
    }

    /** 本周星球周报（滚动 7 天，实时计算）。 */
    @GetMapping("/report")
    @SaCheckLogin
    @Operation(summary = "本周星球周报", description = "返回近 7 天能量曲线、任务完成、连击、新记忆与新徽章的成长报告")
    public ApiResponse<?> report(@RequestParam String deviceId, @RequestParam Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        return ApiResponse.success(weeklyReportService.rolling(deviceId, roleId));
    }

    /** 历史星球周报（已归档的自然周）。 */
    @GetMapping("/reports")
    @SaCheckLogin
    @Operation(summary = "历史星球周报", description = "返回已归档的自然周周报列表，最新在前")
    public ApiResponse<?> reports(@RequestParam String deviceId, @RequestParam Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        return ApiResponse.success(weeklyReportService.history(deviceId, roleId, 12));
    }

    /** 为玩家 APP 生成短期星球码，用于把玩家账号关联到控制台已初始化的 AI。 */
    @GetMapping("/player-code")
    @SaCheckLogin
    @Operation(summary = "生成玩家星球码", description = "玩家在 APP 输入该码后关联自己的 AI，不改变设备的控制台归属")
    public ApiResponse<?> playerCode(@RequestParam String deviceId, @RequestParam Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        DeviceBO device = deviceService.getBO(deviceId);
        if (device == null) {
            return ApiResponse.notFound("设备不存在");
        }
        RoleBO role = roleService.getBO(roleId);
        if (role == null) {
            return ApiResponse.notFound("角色不存在");
        }
        if (device.getUserId() != null && role.getUserId() != null && !device.getUserId().equals(role.getUserId())) {
            return ApiResponse.badRequest("角色与设备不属于同一控制台账号");
        }

        PlayerAgentService.AgentLinkCode code = playerAgentService.createLinkCode(deviceId, roleId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code.code());
        data.put("deviceId", code.deviceId());
        data.put("roleId", code.roleId());
        data.put("roleName", code.roleName());
        data.put("expiresInSeconds", code.expiresInSeconds());
        return ApiResponse.success(data);
    }

    /** 新增/修改一个档案字段（键受白名单约束）。 */
    @PostMapping("/profile")
    @SaCheckLogin
    @Operation(summary = "更新快乐星球档案", description = "新增或修改单个档案字段，键受白名单约束")
    public ApiResponse<?> saveProfile(@RequestBody Map<String, Object> body) {
        String deviceId = asString(body.get("deviceId"));
        Integer roleId = asInteger(body.get("roleId"));
        String field = asString(body.get("field"));
        String value = asString(body.get("value"));
        if (!StringUtils.hasText(deviceId) || roleId == null
                || !StringUtils.hasText(field) || !StringUtils.hasText(value)) {
            return ApiResponse.badRequest("deviceId/roleId/field/value 必填");
        }
        try {
            agentProfileService.updateField(StpUtil.getLoginIdAsInt(), deviceId, roleId, field, value);
            return ApiResponse.success("已更新");
        } catch (IllegalArgumentException e) {
            return ApiResponse.badRequest(e.getMessage());
        }
    }

    /** 删除档案字段（field 为空则清空该智能体全部档案）。 */
    @DeleteMapping("/profile")
    @SaCheckLogin
    @Operation(summary = "删除快乐星球档案", description = "删除单个档案字段；field 为空则清空该智能体全部档案")
    public ApiResponse<?> deleteProfile(@RequestParam String deviceId,
                                        @RequestParam Integer roleId,
                                        @RequestParam(required = false) String field) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            return ApiResponse.badRequest("deviceId 与 roleId 必填");
        }
        int removed = StringUtils.hasText(field)
                ? agentProfileService.deleteField(deviceId, roleId, field)
                : agentProfileService.deleteAll(deviceId, roleId);
        return ApiResponse.success(Map.of("removed", removed));
    }

    private String asString(Object o) {
        return o == null ? null : String.valueOf(o);
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
