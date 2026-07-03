-- ============================================================
-- 成长状态提示词（Growth Prompt）—— 按角色可编辑的动态提示词槽位
-- 说明：
--   「快乐星球」每轮会把成长状态（快乐能量 / 关系阶段 / 时间段 / 频道 /
--   首次见面 / 久别重逢 / 连击 / 任务 / 档案）拼成一段“动态状态提示词”
--   注入 LLM。本迁移把这些原本硬编码在 Java 里的文案，抽成可被运营在
--   管理端逐条编辑的“槽位（slot）”，并支持按角色（roleId）差异化。
--
--   分层覆盖（由 GrowthPromptService 实现）：
--     角色自定义行(roleId>0) → 全局自定义行(roleId=0) → 代码内置默认。
--   槽位目录（key / 标签 / 说明 / 变量 / 默认文案）由 GrowthPromptSlot 枚举
--   在代码中定义，是唯一真源；本表只存“覆盖内容”，未覆盖的槽位一律回退到
--   代码内置默认——因此本迁移只建表、不预置数据，改默认文案无需再写迁移。
--
--   列名沿用项目 camelCase 约定（map-underscore-to-camel-case=false）。
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `sys_growth_prompt`;
CREATE TABLE `sys_growth_prompt` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `roleId` int unsigned NOT NULL DEFAULT 0 COMMENT '角色ID；0=全局默认（对所有未单独覆盖的角色生效）',
  `slotKey` varchar(64) NOT NULL COMMENT '槽位键（对应 GrowthPromptSlot 枚举，如 stage.oldfriend / time.night）',
  `content` text NOT NULL COMMENT '该槽位的提示词文案，支持 {{变量}} 占位',
  `enabled` tinyint NOT NULL DEFAULT 1 COMMENT '是否启用：0-该角色/全局显式关闭此槽位（注入时跳过）1-启用',
  `createTime` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_slot` (`roleId`,`slotKey`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='成长状态提示词槽位覆盖(按角色可编辑,未覆盖回退代码默认)';
