-- ============================================================
-- 待命主动搭话（Proactive Chat）—— 配置、运行时计数与脚本台词
-- 说明：
--   本迁移是「快乐星球」情感陪伴系统的自然延伸，不新增独立功能，
--   而是为“设备自我唤醒后主动开口”提供：可配置的触发变量、运行时
--   护栏计数、以及逐字可审的脚本台词池（不过 LLM，便于甲方审核）。
--
-- 设计红线（对齐 sys_template 快乐星球人设的安全边界）：
--   · 默认全体关闭（enabled=0），开了才有，纯增量，不改变任何现有体验。
--   · 主动 ≠ 打扰：所有阈值均为可配置变量，默认取“适中”。
--   · 硬静默时段绝不主动；当日被忽略则退避；频率有上限、有冷却。
--   · 台词只做正向陪伴：不催促、不施压、不制造监视感、不索取关注、
--     不卖惨、不健康恐吓、不推销。
--
--   列名沿用项目 camelCase 约定（map-underscore-to-camel-case=false），
--   业务维度与 sys_agent_state 一致，均以 (deviceId, roleId) 为键。
-- ============================================================

SET NAMES utf8mb4;

-- ------------------------------------------------------------
-- 1) 主动搭话配置：每台设备/角色一份，全部为“可控变量”，默认适中。
--    无对应行时，代码一律按“关闭”处理（enabled 默认 0）——开关控制留待后续。
-- ------------------------------------------------------------
DROP TABLE IF EXISTS `sys_proactive_config`;
CREATE TABLE `sys_proactive_config` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `deviceId` varchar(255) NOT NULL COMMENT '设备ID',
  `roleId` int unsigned NOT NULL COMMENT '角色ID',
  `enabled` tinyint NOT NULL DEFAULT 0 COMMENT '主动搭话总开关：0-关 1-开（默认关，控制逻辑后续再做）',
  `allowLlm` tinyint NOT NULL DEFAULT 1 COMMENT '是否允许 LLM 增强开场：0-仅脚本台词 1-脚本为主+LLM增强',
  `dailyLimit` int NOT NULL DEFAULT 3 COMMENT '每日主动搭话次数上限（适中默认 3）',
  `cooldownMinutes` int NOT NULL DEFAULT 90 COMMENT '两次主动之间的最小冷却分钟数（适中默认 90=1.5h）',
  `minIdleSeconds` int NOT NULL DEFAULT 600 COMMENT '设备须先静默待命多久才可主动（秒，默认 600=10min）',
  `activeStart` time NOT NULL DEFAULT '09:00:00' COMMENT '活跃时段开始（此段内才考虑主动）',
  `activeEnd` time NOT NULL DEFAULT '21:00:00' COMMENT '活跃时段结束',
  `quietStart` time NOT NULL DEFAULT '22:00:00' COMMENT '硬静默时段开始（绝不主动，优先级高于活跃时段）',
  `quietEnd` time NOT NULL DEFAULT '08:00:00' COMMENT '硬静默时段结束（跨零点）',
  `createTime` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_device_role` (`deviceId`,`roleId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='待命主动搭话配置(可控变量,默认关闭)';

-- ------------------------------------------------------------
-- 2) 主动搭话运行时护栏计数：挂在既有 sys_agent_state 上，用于频率/冷却/退避判定。
-- ------------------------------------------------------------
ALTER TABLE `sys_agent_state`
  ADD COLUMN `lastProactiveTime` datetime(3) NULL COMMENT '最近一次主动搭话时间(用于冷却判定)',
  ADD COLUMN `proactiveDate` date NULL COMMENT '主动搭话计数所属日期(跨天自动归零)',
  ADD COLUMN `proactiveCount` int NOT NULL DEFAULT 0 COMMENT '当日已主动搭话次数(对照 dailyLimit)',
  ADD COLUMN `proactiveIgnoredToday` tinyint NOT NULL DEFAULT 0 COMMENT '当日主动搭话是否被忽略:1=已被忽略,当天退避不再主动';

-- ============================================================
-- 3) 脚本台词池（逐字可审，不过 LLM）
--    注意：主动搭话走“同场景内随机取一句”的语义（由服务端 randomLine(sceneKey) 实现），
--    与既有 speakScenes 的“顺序播全部”不同——所以下面每个 seq 都是一条可独立成句的变体。
--    夜间不设主动台词：22:00-08:00 属硬静默，绝不主动开口。
-- ============================================================

-- 早间问候：温柔、不说教、把节奏交还给用户
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('proactive_morning',1,'早呀，新的一天开始啦。不用急，慢慢来就好，我在这儿陪着你。','happy'),
('proactive_morning',2,'早上好呀，今天也会是温柔的一天。需要我的时候，随时叫我。','happy'),
('proactive_morning',3,'醒啦？我先跟你说声早，剩下的节奏，你自己说了算。','happy');

-- 久坐/久静默后的关心：关心但无监视感，不施压
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('proactive_care',1,'忙了好一会儿啦，记得起来动动、喝口水，我等你回来。','neutral'),
('proactive_care',2,'小提醒一下：坐久了就伸个懒腰吧，不着急，弄完再说。','neutral'),
('proactive_care',3,'路过看你一眼，别太拼啦，累了就歇一歇。','neutral');

-- 陪伴邀请：必须给选择权、明确留边界（沿用 energy_repair 的“给选择权”范式）
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('proactive_invite',1,'要不要我陪你聊两句？想安静的话，我就乖乖待着，不打扰你。','happy'),
('proactive_invite',2,'有点想你了，不过你要是在忙，就当我没说～想聊了随时喊我。','happy'),
('proactive_invite',3,'在的话吱一声？不方便也没关系，我一直都在。','neutral');

-- 正向鼓励：只给暖意，不灌鸡汤、不诱导依赖
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('proactive_cheer',1,'突然想跟你说一句：你今天已经很棒了，真的。','happy'),
('proactive_cheer',2,'没什么事，就是想让你知道——有我在呢。','happy'),
('proactive_cheer',3,'给你充一点快乐能量：嘿，你比自己想象的更好。','happy');

-- 每日星球任务引导前缀：非强制、可拒绝；任务正文另取自 sys_planet_task
INSERT INTO `sys_script_lines` (`sceneKey`,`seq`,`content`,`emotion`) VALUES
('proactive_task',1,'今日星球任务到啦，很轻的那种，愿意的话听听看？','happy'),
('proactive_task',2,'有个小小的星球任务想分享给你，不想做也完全没关系哦。','happy');
