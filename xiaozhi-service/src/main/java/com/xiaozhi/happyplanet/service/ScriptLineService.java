package com.xiaozhi.happyplanet.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ScriptLineDO;
import com.xiaozhi.happyplanet.dal.mysql.mapper.ScriptLineMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 品牌台词库读取服务。仪式性 / 频道过场台词逐字播报，不经过 LLM，便于甲方审核。
 */
@Service
public class ScriptLineService {

    @Resource
    private ScriptLineMapper scriptLineMapper;

    /** 按场景取全部启用台词，按 seq 升序。 */
    public List<ScriptLineDO> lines(String sceneKey) {
        return scriptLineMapper.selectList(new LambdaQueryWrapper<ScriptLineDO>()
                .eq(ScriptLineDO::getSceneKey, sceneKey)
                .eq(ScriptLineDO::getEnabled, 1)
                .orderByAsc(ScriptLineDO::getSeq));
    }

    /** 取某场景第一条台词（seq 最小），无则返回 null。 */
    public ScriptLineDO firstLine(String sceneKey) {
        List<ScriptLineDO> lines = lines(sceneKey);
        return lines.isEmpty() ? null : lines.get(0);
    }

    /** 从某场景随机取一条台词（用于“多变体择一”的仪式，如授勋/补签），无则返回 null。 */
    public ScriptLineDO randomLine(String sceneKey) {
        List<ScriptLineDO> lines = lines(sceneKey);
        if (lines.isEmpty()) {
            return null;
        }
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }
}
