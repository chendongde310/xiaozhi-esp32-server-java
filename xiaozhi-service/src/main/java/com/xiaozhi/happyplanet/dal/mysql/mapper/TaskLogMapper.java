package com.xiaozhi.happyplanet.dal.mysql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.TaskLogDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TaskLogMapper extends BaseMapper<TaskLogDO> {
}
