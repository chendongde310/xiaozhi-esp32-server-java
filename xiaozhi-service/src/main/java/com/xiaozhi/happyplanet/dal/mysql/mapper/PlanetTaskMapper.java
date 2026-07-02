package com.xiaozhi.happyplanet.dal.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.PlanetTaskDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PlanetTaskMapper extends BaseMapper<PlanetTaskDO> {
}
