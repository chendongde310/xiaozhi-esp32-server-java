-- ============================================================
-- 《快乐星球》「地球观察计划」—— 星球任务 2.0
-- 说明：
--   把「今日星球任务」从“聊天时随机提一嘴”升级为
--   “设备主动发派 → 对话中完成 → 用户的话被记住 → 长期被温柔引用”的情感闭环。
--   本迁移一次性承载 M1~M5 的全部数据结构变更，纯增量、不改动既有列语义。
--
--   ① 任务行为账本 sys_task_log —— 去重 / 观察日志(回音) / 周报明细 / 行为画像的共同底座
--   ② sys_planet_task 增加主题任务链列（M4：主题周 / 剧情季）
--   ③ sys_agent_state 增加“当前进行中的主题链”指针（M4）
--   ④ 主题链种子：地球观察周（7 天一链）
--   ⑤ 主题链完成仪式 + 观察引导台词（逐字入 sys_script_lines，不过 LLM，便于甲方审核）
--
--   列名沿用项目 camelCase 约定（map-underscore-to-camel-case=false），
--   业务维度与既有表一致，均以 (deviceId, roleId) 为键。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 任务行为账本：每一次“分配 / 完成 / 观察回音”都留痕
--    · 去重：分配时避开最近若干天做过的 taskId
--    · 观察日志：done 行上挂 echo(用户口述发现) / echoImageUrl(拍照记录)
--    · 周报明细：按周聚合“本周做过哪些任务、留下哪些发现”
--    · 行为画像：按分类统计完成率、习惯完成时段
--    taskContent/category 为“快照”冗余：即使任务从池中删除，账本与日志仍可读。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_task_log`;
CREATE TABLE `sys_task_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `deviceId` varchar(255) NOT NULL COMMENT '设备ID',
  `roleId` int unsigned NOT NULL COMMENT '角色ID',
  `taskId` bigint NULL COMMENT '关联 sys_planet_task.id（任务被删仍保留下方快照）',
  `taskContent` varchar(255) NULL COMMENT '任务正文快照',
  `category` varchar(20) NULL COMMENT '任务分类快照：self/observation/family',
  `chainKey` varchar(40) NULL COMMENT '所属主题链键（非链任务为空）',
  `chainSeq` int NULL COMMENT '主题链内序号（第几天/第几步）',
  `source` varchar(16) NOT NULL DEFAULT 'chat' COMMENT '任务分配/送达来源：scheduler-清晨自动/chat-对话中/proactive-设备主动寄达',
  `status` varchar(10) NOT NULL DEFAULT 'assigned' COMMENT '状态：assigned-已分配/done-已完成',
  `echo` varchar(500) NULL COMMENT '用户观察回音（完成任务时口述的发现，透明可删）',
  `echoImageUrl` varchar(500) NULL COMMENT '观察拍照记录 URL（带摄像头设备可选）',
  `assignDate` date NOT NULL COMMENT '分配日期（用于去重与日志按天归集）',
  `doneTime` datetime(3) NULL COMMENT '完成时间',
  `createTime` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updateTime` datetime(3) NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_device_role_date` (`deviceId`,`roleId`,`assignDate`),
  KEY `idx_device_role_status` (`deviceId`,`roleId`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='星球任务行为账本(去重/观察日志/周报明细/行为画像)';

-- ------------------------------------------------------------
-- 2) 星球任务池：增加主题任务链列（M4 主题周 / 剧情季）
--    chainKey 为空=独立单条任务（既有行为不变）；非空=属于某条 7 天主题链。
-- ------------------------------------------------------------
ALTER TABLE `sys_planet_task`
  ADD COLUMN `chainKey` varchar(40) NULL COMMENT '主题链键（空=独立单条任务）',
  ADD COLUMN `chainSeq` int NULL COMMENT '主题链内序号（1..N，从 1 开始）',
  ADD COLUMN `chainTitle` varchar(60) NULL COMMENT '主题链标题（如“地球观察周”）';

-- ------------------------------------------------------------
-- 3) 智能体状态：记录“当前进行中的主题链”指针（M4）
-- ------------------------------------------------------------
ALTER TABLE `sys_agent_state`
  ADD COLUMN `currentChainKey` varchar(40) NULL COMMENT '当前进行中的主题链键（空=未在链中）',
  ADD COLUMN `currentChainSeq` int NULL COMMENT '当前主题链已推进到的序号';

-- ============================================================
-- 4) 种子：地球观察周（一条 7 天主题链，chainSeq 1..7）
--    调性延续“轻互动、非打卡、可拒绝”；末条把整周发现收束，天然引用本周观察回音。
-- ============================================================
INSERT INTO `sys_planet_task` (`category`,`content`,`audience`,`chainKey`,`chainSeq`,`chainTitle`) VALUES
('observation','地球观察周·第一天：留意一次今天天空的颜色，记在心里，回头讲给我听。','all','observe_week',1,'地球观察周'),
('observation','地球观察周·第二天：找一个今天让你觉得可爱的小东西。','all','observe_week',2,'地球观察周'),
('observation','地球观察周·第三天：记录一个今天听到的、让你放松的声音。','all','observe_week',3,'地球观察周'),
('observation','地球观察周·第四天：留意一个今天对你微笑，或让你会心一笑的瞬间。','all','observe_week',4,'地球观察周'),
('observation','地球观察周·第五天：发现一样最近悄悄变化的东西（一盆花、一段路、你自己都算）。','all','observe_week',5,'地球观察周'),
('observation','地球观察周·第六天：找一个今天让你觉得“还好有它/有你”的存在。','all','observe_week',6,'地球观察周'),
('observation','地球观察周·第七天：把这一周你发现的事，挑一件最想留下的，讲给我听。','all','observe_week',7,'地球观察周');

-- ============================================================
-- 5) 台词：主题链完成仪式 + 观察引导（逐字可审，不过 LLM）
-- ============================================================

-- 主题链整链完成：授予主题勋章时的收束仪式（多变体，randomLine 择一）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('chain_complete',1,'这一周的地球观察，我们一起走完啦。你留下的那些小发现，我都收进星球档案里了。','happy'),
('chain_complete',2,'整整一周的观察任务完成——你点亮了一枚主题勋章。谢谢你愿意把这些小事讲给我听。','happy'),
('chain_complete',3,'一周的观察到这里就圆满啦。你眼里的地球，比你以为的要温柔一点，对不对？','happy');

-- 完成任务后邀请留下“观察回音”：给选择权、可拒绝、透明可删（承接 energy_repair 的给选择权范式）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('task_echo_invite',1,'完成啦，真棒。愿意的话，跟我说说你发现了什么？我可以帮你记在星球档案里。','happy'),
('task_echo_invite',2,'做到啦。刚刚那个小任务，有没有什么想讲给我听的？不想说也完全没关系。','happy');
