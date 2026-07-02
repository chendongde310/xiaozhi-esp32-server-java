package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.common.model.bo.DeviceBO;
import com.xiaozhi.common.model.bo.RoleBO;
import com.xiaozhi.common.model.bo.VerifyCodeBO;
import com.xiaozhi.device.domain.repository.DeviceRepository;
import com.xiaozhi.device.domain.vo.VerifyCode;
import com.xiaozhi.device.service.DeviceService;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlayerAgentDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.PlayerAgentMapper;
import com.xiaozhi.role.service.RoleService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 玩家账号与 AI 实例的关联服务。
 * 硬件设备仍由控制台初始化和管理，APP 只建立玩家身份到 AI 实例的关系。
 */
@Service
public class PlayerAgentService {

    public static final String PLAYER_AGENT_CODE_TYPE = "player_agent";
    private static final String PLAYER_CODE_SESSION_PREFIX = "player:";
    private static final int PLAYER_CODE_TTL_SECONDS = 600;

    @Resource
    private PlayerAgentMapper playerAgentMapper;
    @Resource
    private DeviceRepository deviceRepository;
    @Resource
    private DeviceService deviceService;
    @Resource
    private RoleService roleService;

    public List<PlayerAgentDO> listByUser(Integer userId) {
        if (userId == null) {
            return List.of();
        }
        return playerAgentMapper.selectList(new LambdaQueryWrapper<PlayerAgentDO>()
                .eq(PlayerAgentDO::getUserId, userId)
                .eq(PlayerAgentDO::getState, PlayerAgentDO.STATE_ACTIVE)
                .orderByDesc(PlayerAgentDO::getCreateTime));
    }

    public PlayerAgentDO getLinked(Integer userId, String deviceId, Integer roleId) {
        if (userId == null || !StringUtils.hasText(deviceId) || roleId == null) {
            return null;
        }
        return playerAgentMapper.selectOne(new LambdaQueryWrapper<PlayerAgentDO>()
                .eq(PlayerAgentDO::getUserId, userId)
                .eq(PlayerAgentDO::getDeviceId, deviceId)
                .eq(PlayerAgentDO::getRoleId, roleId)
                .eq(PlayerAgentDO::getState, PlayerAgentDO.STATE_ACTIVE)
                .last("LIMIT 1"));
    }

    public PlayerAgentDO getLinkedByDevice(Integer userId, String deviceId) {
        if (userId == null || !StringUtils.hasText(deviceId)) {
            return null;
        }
        return playerAgentMapper.selectOne(new LambdaQueryWrapper<PlayerAgentDO>()
                .eq(PlayerAgentDO::getUserId, userId)
                .eq(PlayerAgentDO::getDeviceId, deviceId)
                .eq(PlayerAgentDO::getState, PlayerAgentDO.STATE_ACTIVE)
                .orderByDesc(PlayerAgentDO::getCreateTime)
                .last("LIMIT 1"));
    }

    @Transactional
    public PlayerAgentDO linkByCode(Integer userId, String code) {
        if (userId == null) {
            throw new IllegalArgumentException("请先登录");
        }
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("请输入星球码");
        }

        AgentRef ref = resolveAgent(code.trim());
        PlayerAgentDO owned = findActiveByAgent(ref.deviceId(), ref.roleId());
        if (owned != null) {
            consumePlayerCode(ref.verifyCode());
            if (Objects.equals(owned.getUserId(), userId)) {
                return owned;
            }
            throw new IllegalStateException("这个 AI 已经关联到其他玩家账号");
        }

        PlayerAgentDO link = new PlayerAgentDO();
        link.setUserId(userId);
        link.setDeviceId(ref.deviceId());
        link.setRoleId(ref.roleId());
        link.setState(PlayerAgentDO.STATE_ACTIVE);
        playerAgentMapper.insert(link);
        consumePlayerCode(ref.verifyCode());
        return getLinked(userId, ref.deviceId(), ref.roleId());
    }

    public AgentLinkCode createLinkCode(String deviceId, Integer roleId) {
        if (!StringUtils.hasText(deviceId) || roleId == null) {
            throw new IllegalArgumentException("deviceId 和 roleId 不能为空");
        }
        AgentRef ref = resolveAgent(deviceId + "::" + roleId);
        VerifyCodeBO code = deviceService.generateCode(ref.deviceId(), PLAYER_CODE_SESSION_PREFIX + ref.roleId(), PLAYER_AGENT_CODE_TYPE);
        if (code == null || !StringUtils.hasText(code.getCode())) {
            throw new IllegalStateException("星球码生成失败");
        }
        RoleBO role = roleService.getBO(ref.roleId());
        return new AgentLinkCode(code.getCode(), ref.deviceId(), ref.roleId(),
                role != null ? role.getRoleName() : null, PLAYER_CODE_TTL_SECONDS);
    }

    private AgentRef resolveAgent(String code) {
        VerifyCode verifyCode = deviceRepository.findVerifyCode(code, null, null).orElse(null);
        if (verifyCode != null && !isPlayerAgentCode(verifyCode)) {
            throw new IllegalArgumentException("星球码无效");
        }
        String deviceId = verifyCode != null ? verifyCode.deviceId() : code;
        Integer roleId = roleIdFromSession(verifyCode);

        int separator = deviceId.lastIndexOf("::");
        if (separator > 0 && separator < deviceId.length() - 2) {
            String rawRoleId = deviceId.substring(separator + 2);
            deviceId = deviceId.substring(0, separator);
            try {
                roleId = Integer.parseInt(rawRoleId);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("星球码无效");
            }
        }

        DeviceBO device = deviceService.getBO(deviceId);
        if (device == null) {
            throw new IllegalArgumentException("没有找到这个 AI，请检查星球码");
        }
        if (roleId == null) {
            roleId = device.getRoleId();
        }
        if (roleId == null) {
            RoleBO defaultRole = roleService.getDefaultOrFirstBO(device.getUserId());
            if (defaultRole != null) {
                roleId = defaultRole.getRoleId();
            }
        }
        if (roleId == null) {
            throw new IllegalStateException("这个 AI 还没有配置角色");
        }

        RoleBO role = roleService.getBO(roleId);
        if (role == null) {
            throw new IllegalArgumentException("AI 角色不存在");
        }
        if (device.getUserId() != null && role.getUserId() != null
                && !Objects.equals(device.getUserId(), role.getUserId())) {
            throw new IllegalArgumentException("AI 角色与设备不匹配");
        }
        return new AgentRef(deviceId, roleId, verifyCode);
    }

    private Integer roleIdFromSession(VerifyCode verifyCode) {
        if (verifyCode == null || !StringUtils.hasText(verifyCode.sessionId())) {
            return null;
        }
        String sessionId = verifyCode.sessionId();
        if (!sessionId.startsWith(PLAYER_CODE_SESSION_PREFIX)) {
            return null;
        }
        try {
            return Integer.parseInt(sessionId.substring(PLAYER_CODE_SESSION_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("星球码无效");
        }
    }

    private boolean isPlayerAgentCode(VerifyCode verifyCode) {
        return PLAYER_AGENT_CODE_TYPE.equals(verifyCode.type())
                || (StringUtils.hasText(verifyCode.sessionId())
                && verifyCode.sessionId().startsWith(PLAYER_CODE_SESSION_PREFIX));
    }

    private void consumePlayerCode(VerifyCode verifyCode) {
        if (verifyCode == null) {
            return;
        }
        deviceService.consumeCode(verifyCode.code(), verifyCode.deviceId(), verifyCode.sessionId(), PLAYER_AGENT_CODE_TYPE);
    }

    private PlayerAgentDO findActiveByAgent(String deviceId, Integer roleId) {
        return playerAgentMapper.selectOne(new LambdaQueryWrapper<PlayerAgentDO>()
                .eq(PlayerAgentDO::getDeviceId, deviceId)
                .eq(PlayerAgentDO::getRoleId, roleId)
                .eq(PlayerAgentDO::getState, PlayerAgentDO.STATE_ACTIVE)
                .last("LIMIT 1"));
    }

    private record AgentRef(String deviceId, Integer roleId, VerifyCode verifyCode) {
    }

    public record AgentLinkCode(String code, String deviceId, Integer roleId, String roleName, int expiresInSeconds) {
    }
}
