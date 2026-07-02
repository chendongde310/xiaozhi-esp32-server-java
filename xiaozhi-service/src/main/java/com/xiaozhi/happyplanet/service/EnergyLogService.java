package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.EnergyLogDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.EnergyLogMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 能量事件流水服务：每次快乐能量变化追加一行，支撑“能量曲线”与情绪陪伴数据资产。
 *
 * <p>写入为“尽力而为”：任何异常都被吞掉并告警，绝不影响主对话/任务路径。
 */
@Slf4j
@Service
public class EnergyLogService {

    public static final String REASON_INTERACTION = "interaction";
    public static final String REASON_TASK = "task";
    public static final String REASON_DECAY = "decay";
    public static final String REASON_REPAIR = "repair";

    @Resource
    private EnergyLogMapper energyLogMapper;

    /** 记录一次能量变化（delta=0 时跳过，避免无效噪声）。 */
    public void record(String deviceId, Integer roleId, int energy, int delta, String reason) {
        if (delta == 0 || deviceId == null || roleId == null) {
            return;
        }
        try {
            EnergyLogDO row = new EnergyLogDO();
            row.setDeviceId(deviceId);
            row.setRoleId(roleId);
            row.setEnergy(energy);
            row.setDelta(delta);
            row.setReason(reason);
            LocalDateTime now = LocalDateTime.now();
            row.setLogTime(now);
            row.setLogDate(now.toLocalDate());
            energyLogMapper.insert(row);
        } catch (Exception e) {
            log.warn("记录能量事件失败 deviceId={}, roleId={}, reason={}", deviceId, roleId, reason, e);
        }
    }

    /** 取自 from（含）起的能量事件，按时间升序。 */
    public List<EnergyLogDO> since(String deviceId, Integer roleId, LocalDate from) {
        return energyLogMapper.selectList(new LambdaQueryWrapper<EnergyLogDO>()
                .eq(EnergyLogDO::getDeviceId, deviceId)
                .eq(EnergyLogDO::getRoleId, roleId)
                .ge(EnergyLogDO::getLogDate, from)
                .orderByAsc(EnergyLogDO::getLogTime));
    }

    /** 统计区间 [from, to] 内某类原因的事件条数（如本周完成任务次数）。 */
    public int countByReason(String deviceId, Integer roleId, LocalDate from, LocalDate to, String reason) {
        return Math.toIntExact(energyLogMapper.selectCount(new LambdaQueryWrapper<EnergyLogDO>()
                .eq(EnergyLogDO::getDeviceId, deviceId)
                .eq(EnergyLogDO::getRoleId, roleId)
                .eq(EnergyLogDO::getReason, reason)
                .ge(EnergyLogDO::getLogDate, from)
                .le(EnergyLogDO::getLogDate, to)));
    }
}
