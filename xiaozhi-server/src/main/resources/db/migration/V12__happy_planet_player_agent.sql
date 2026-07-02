-- 用户端 APP 不直接绑定硬件设备；硬件先归属控制台，再由玩家账号关联自己的 AI 实例。
-- 一个 AI 实例当前只允许一个玩家账号关联，后续家庭成员/共享账号可在此表上扩展关系类型。

CREATE TABLE IF NOT EXISTS `sys_player_agent` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT COMMENT '主键',
  `userId` int unsigned NOT NULL COMMENT '玩家用户ID',
  `deviceId` varchar(255) NOT NULL COMMENT '控制台已初始化的设备ID',
  `roleId` int unsigned NOT NULL COMMENT 'AI角色ID',
  `state` enum('1','0') COLLATE utf8mb4_unicode_ci DEFAULT '1' COMMENT '1-有效 0-解除关联',
  `createTime` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_player_agent` (`userId`,`deviceId`,`roleId`,`state`),
  UNIQUE KEY `uk_agent_owner` (`deviceId`,`roleId`,`state`),
  KEY `idx_player` (`userId`,`state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='玩家账号与AI实例关联表';
