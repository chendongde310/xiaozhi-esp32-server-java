package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlanetTaskDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.PlanetTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 星球任务池读取服务。
 */
@Service
public class PlanetTaskService {

    @Resource
    private PlanetTaskMapper planetTaskMapper;

    /**
     * 随机取一条面向指定人群的启用任务（audience 匹配目标或 'all'）。无则返回 null。
     */
    public PlanetTaskDO randomTask(String audience) {
        LambdaQueryWrapper<PlanetTaskDO> wrapper = new LambdaQueryWrapper<PlanetTaskDO>()
                .eq(PlanetTaskDO::getEnabled, 1);
        if (StringUtils.hasText(audience) && !"all".equals(audience)) {
            wrapper.and(w -> w.eq(PlanetTaskDO::getAudience, audience)
                    .or().eq(PlanetTaskDO::getAudience, "all"));
        }
        List<PlanetTaskDO> tasks = planetTaskMapper.selectList(wrapper);
        if (tasks.isEmpty()) {
            return null;
        }
        return tasks.get(ThreadLocalRandom.current().nextInt(tasks.size()));
    }

    public PlanetTaskDO getById(Long id) {
        return id == null ? null : planetTaskMapper.selectById(id);
    }

    public List<PlanetTaskDO> listAll() {
        return planetTaskMapper.selectList(new LambdaQueryWrapper<PlanetTaskDO>()
                .eq(PlanetTaskDO::getEnabled, 1)
                .orderByAsc(PlanetTaskDO::getCategory));
    }
}
