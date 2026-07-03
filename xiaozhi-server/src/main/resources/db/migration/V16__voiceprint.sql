-- ============================================================
-- 声纹识别（Voiceprint）—— 用户声纹特征映射
-- 说明：
--   接入讯飞声纹识别（新版，服务 id s1aa729d0）。每个玩家账号（userId）
--   最多录入「唯一一个」声纹特征：本表以 userId 为唯一键强约束一人一声纹。
--
--   · 讯飞侧数据模型：一个 group（声纹特征库）下挂多个 feature（声纹特征）。
--     本项目用同一个 groupId 承载所有用户，每个用户对应一个 featureId=`u{userId}`。
--   · 设备语音每轮对话时，服务端用当轮 PCM 对该用户 featureId 做 1:1 比对
--     （searchScoreFea），score≥阈值判定为「主人」，否则「访客」→ 角色进入边界模式。
--   · fail-open 原则：未录入 / 音频过短 / 接口异常一律按普通对话，绝不因故障锁住主人。
--
--   列名沿用项目 camelCase 约定（map-underscore-to-camel-case=false）。
-- ============================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `sys_user_voiceprint`;
CREATE TABLE `sys_user_voiceprint` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `userId` int unsigned NOT NULL COMMENT '玩家账号ID（一人一声纹，唯一）',
  `groupId` varchar(64) NOT NULL COMMENT '讯飞声纹特征库标识',
  `featureId` varchar(64) NOT NULL COMMENT '讯飞声纹特征标识（形如 u{userId}）',
  `sampleRate` int NOT NULL DEFAULT 16000 COMMENT '录入音频采样率',
  `audioPath` varchar(512) NULL DEFAULT NULL COMMENT '录入样本音频存储路径（可空，仅供回溯/重录）',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态：0-无效 1-有效',
  `createTime` datetime NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user` (`userId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户声纹特征映射(一人一声纹)';
