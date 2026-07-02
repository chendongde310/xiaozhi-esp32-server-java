-- ============================================================
-- 《快乐星球》成长体系（P1+P2）—— 羁绊阶段 / 任务连击 / 徽章 /
--   纪念日仪式 / 能量曲线 / 星球周报
-- 说明：
--   纯增量迁移，不改动既有列语义，不影响现有体验。
--   业务维度与既有表一致，均以 (deviceId, roleId) 为键。
--   列名沿用项目 camelCase 约定（map-underscore-to-camel-case=false）。
--   仪式/周报台词继续逐字入 sys_script_lines，不过 LLM，便于甲方审核。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 在既有 sys_agent_state 上挂载成长运行时字段
--    · 连击（streak）：连续完成每日星球任务的天数 + 历史最长 + 补签卡
--    · 羁绊阶段仪式去重：stage 记录“最近一次已庆祝到的阶段”
--    · 纪念日仪式去重：lastAnniversary 记录“最近一次已庆祝的陪伴里程碑天数”
--    展示用的“当前阶段”始终由 companionDays 实时推导，这里的 stage/
--    lastAnniversary 仅用于保证升级/纪念仪式只播报一次。
-- ------------------------------------------------------------
ALTER TABLE `sys_agent_state`
  ADD COLUMN `streakDays` int NOT NULL DEFAULT 0 COMMENT '当前连续完成每日任务的天数',
  ADD COLUMN `bestStreak` int NOT NULL DEFAULT 0 COMMENT '历史最长连击',
  ADD COLUMN `lastStreakDate` date NULL COMMENT '最近一次让连击+1的日期(判断连续/断签)',
  ADD COLUMN `streakRepairLeft` int NOT NULL DEFAULT 1 COMMENT '剩余能量修复卡(补签卡)张数',
  ADD COLUMN `streakRepairMonth` varchar(7) NULL COMMENT '补签卡发放所属月份(yyyy-MM),跨月自动补充',
  ADD COLUMN `stage` int NOT NULL DEFAULT 1 COMMENT '最近一次已庆祝到的羁绊阶段:1初识/2熟悉/3老朋友/4星球密友',
  ADD COLUMN `lastAnniversary` int NOT NULL DEFAULT 0 COMMENT '最近一次已庆祝的陪伴里程碑天数(7/30/100/365)';

-- ------------------------------------------------------------
-- 2) 星球徽章：连击/阶段/纪念日达成后授予，逐枚展示于徽章墙
--    badgeKey 由代码侧徽章目录约束；(deviceId,roleId,badgeKey) 唯一即幂等。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_agent_badge`;
CREATE TABLE `sys_agent_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `deviceId` varchar(255) NOT NULL COMMENT '设备ID',
  `roleId` int unsigned NOT NULL COMMENT '角色ID',
  `badgeKey` varchar(50) NOT NULL COMMENT '徽章键(代码侧目录约束)',
  `earnDate` date NULL COMMENT '获得日期',
  `createTime` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '获得时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_role_badge` (`deviceId`,`roleId`,`badgeKey`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='星球徽章(连击/阶段/纪念日成就)';

-- ------------------------------------------------------------
-- 3) 能量事件流水：每次快乐能量变化追加一行，支撑“能量曲线”与情绪陪伴数据资产
--    reason: interaction-对话/task-完成任务/decay-跨天衰减/repair-补签/manual-人工
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_energy_log`;
CREATE TABLE `sys_energy_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `deviceId` varchar(255) NOT NULL COMMENT '设备ID',
  `roleId` int unsigned NOT NULL COMMENT '角色ID',
  `energy` int NOT NULL COMMENT '变化后的能量值(0-100)',
  `delta` int NOT NULL DEFAULT 0 COMMENT '本次能量增量(可为负)',
  `reason` varchar(30) NOT NULL DEFAULT 'interaction' COMMENT '变化原因:interaction/task/decay/repair/manual',
  `logDate` date NOT NULL COMMENT '事件日期(用于按天聚合曲线)',
  `logTime` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事件时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_role_date` (`deviceId`,`roleId`,`logDate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='能量事件流水(能量曲线/情绪数据资产)';

-- ------------------------------------------------------------
-- 4) 星球周报：每周日定时生成并归档，支撑“周报卡片 + 分享图 + 回流”
--    当周滚动预览走实时计算不落库；此表仅存已完成的周报，供历史回看与分析。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_weekly_report`;
CREATE TABLE `sys_weekly_report` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `deviceId` varchar(255) NOT NULL COMMENT '设备ID',
  `roleId` int unsigned NOT NULL COMMENT '角色ID',
  `periodStart` date NOT NULL COMMENT '统计周期开始(周一)',
  `periodEnd` date NOT NULL COMMENT '统计周期结束(周日)',
  `energyStart` int NULL COMMENT '周初能量',
  `energyEnd` int NULL COMMENT '周末能量',
  `energyAvg` int NULL COMMENT '周内平均能量',
  `energyMin` int NULL COMMENT '周内最低能量',
  `energyMax` int NULL COMMENT '周内最高能量',
  `energyCurve` varchar(255) NULL COMMENT '每日能量点(JSON int 数组)',
  `tasksDone` int NOT NULL DEFAULT 0 COMMENT '本周完成星球任务次数',
  `streakDays` int NOT NULL DEFAULT 0 COMMENT '周末时的连击天数',
  `companionDays` int NOT NULL DEFAULT 0 COMMENT '周末时的累计陪伴天数',
  `stage` int NOT NULL DEFAULT 1 COMMENT '周末时的羁绊阶段',
  `badgesEarned` int NOT NULL DEFAULT 0 COMMENT '本周新得徽章数',
  `newMemories` text NULL COMMENT '本周新记住的档案(JSON 数组[{label,value}])',
  `highlight` varchar(255) NULL COMMENT '本周专属寄语(逐字台词)',
  `createTime` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_role_period` (`deviceId`,`roleId`,`periodStart`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='星球周报(每周成长报告归档)';

-- ============================================================
-- 种子台词：仪式 / 周报寄语（逐字可审，不过 LLM）
--   气质对齐快乐星球人设：温暖、有边界、不煽情、不诱导依赖。
-- ============================================================

-- 羁绊阶段升级仪式（当陪伴天数跨过阶段阈值，重连时播报一次）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('stage_up_familiar',1,'我发现，我们好像没那么陌生了。','happy'),
('stage_up_familiar',2,'从今天起，我把你放进“熟悉的人”那一栏。以后聊起来，会更自在一点。','happy'),
('stage_up_oldfriend',1,'不知不觉，我们已经认识挺久了。','happy'),
('stage_up_oldfriend',2,'你现在是我的老朋友啦。有些话不用从头解释，我大概都懂。','happy'),
('stage_up_soulmate',1,'想跟你说一件事：在我的星球档案里，你已经是最特别的那一个。','happy'),
('stage_up_soulmate',2,'星球密友，这是我能给的最高称呼了。谢谢你一直都在。','happy');

-- 纪念日仪式（陪伴里程碑，重连时播报一次）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('anniversary_7',1,'小小的纪念一下：我们已经相伴一整周啦。','happy'),
('anniversary_7',2,'这一周你辛苦了，也谢谢你愿意让我陪着。','happy'),
('anniversary_30',1,'今天是我们相伴的第三十天，一个值得记一笔的日子。','happy'),
('anniversary_30',2,'一个月里的开心和疲惫，我都收进了你的快乐星球档案。','happy'),
('anniversary_100',1,'第一百天到啦——这是属于我们的百日同行。','happy'),
('anniversary_100',2,'能陪你走到这里，我很开心。接下来的日子，我还在。','happy'),
('anniversary_365',1,'整整一年了。谢谢你，让我陪你走过了一圈四季。','happy');

-- 授勋（点亮徽章，逐字通用，具体徽章名在 App 徽章墙展示）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('badge_awarded',1,'叮——你点亮了一枚新的星球徽章。这份坚持，我都看在眼里。','happy'),
('badge_awarded',2,'又一枚徽章收入囊中。你比自己以为的更能坚持。','happy'),
('badge_awarded',3,'新徽章到手啦，回头去徽章墙看看，它在发光呢。','happy');

-- 连击补签（能量修复卡自动补上断掉的一天）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('streak_repaired',1,'昨天断了一下没关系，我用掉一张能量修复卡，帮你把连击接上了。','happy'),
('streak_repaired',2,'偶尔漏一天很正常，我悄悄帮你补签了，连击还在。','happy');

-- 周报生成后的专属寄语（周报卡片文案随机取一句）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('weekly_report_closing',1,'这一周就先收进星球档案啦。下一周，我们慢慢来。','happy'),
('weekly_report_closing',2,'新的一周，不用给自己太大压力，我一直在这条频道上。','happy'),
('weekly_report_closing',3,'谢谢你这一周的每一次连接。下周见，地球伙伴。','happy');
