# 快乐星球 · 成长体系（P1+P2）交付说明与演示脚本

在 Demo 基础上落地了 Roadmap 的 **P1（羁绊阶段 / 任务连击 / 徽章 / 纪念日仪式）** 与
**P2（星球周报+分享卡 / 能量曲线 / 记忆星图）**。全部逐字仪式台词继续走 `sys_script_lines`（不过 LLM，甲方可逐字审核）。

## 一、交付清单

| 能力 | 用户可感 | 主要文件 |
|---|---|---|
| 羁绊阶段 | 初识→熟悉→老朋友→星球密友，跨阈值重连播报升级仪式，语气随阶段更亲近 | `CompanionStage`、`GrowthService`、`MessageHandler.maybeGrowthRituals` |
| 任务连击 | 连续完成每日任务累计连击；断一天自动用「能量修复卡」补签（每月 1 张，温和不焦虑） | `AgentStateService.markTaskDone/settleStreakOnTaskDone` |
| 星球徽章 | 连击 3/7/21、阶段、纪念日成就，App 徽章墙点亮，达成时设备授勋播报 | `BadgeCatalog`、`BadgeService`、`CompleteTaskFunction` |
| 纪念日仪式 | 陪伴第 7/30/100/365 天重连播报纪念台词 + 纪念徽章 | `GrowthService.claimConnectRituals`、`anniversary_*` 台词 |
| 能量曲线 | 每次能量变化落流水，App/管理端展示近 7 天曲线 | `EnergyLogService`、`sys_energy_log`、`AgentRuntimeService.energyCurve` |
| 星球周报 | 每周日归档，App 滚动 7 天预览 +「生成分享图」（含品牌与邀请文案，裂变入口） | `WeeklyReportService`、`sys_weekly_report`、`HappyPlanetTaskScheduler.generateWeeklyReports` |
| 记忆星图 | 档案目录随羁绊阶段分层解锁，App 星图可视化“已点亮/未记录/未解锁” | `AgentProfileService`（分层白名单 + `starMap`） |

数据表（Flyway `V14__happy_planet_growth.sql`，纯增量）：`sys_agent_state` 加 streak/stage/纪念去重列；
新 `sys_agent_badge`、`sys_energy_log`、`sys_weekly_report`；新增仪式/周报台词种子。

## 二、接口

- 管理端 `GET /api/happyplanet/profile` 的返回已扩展 `stage / streak / badges / memoryStarMap / energyCurve`。
- 管理端 `GET /api/happyplanet/report`、`/reports`（本周滚动 / 历史归档）。
- App `GET /api/app/planet`（同样扩展）、`GET /api/app/report`、`/reports`。

## 三、逐个演示脚本

### 1. 任务连击 + 徽章（P1 主打，留存钩子）
- 连续两天让设备分配到「今日星球任务」，对 AI 说“我完成了今天的星球任务”。
- 第 3 天完成时：连击达 3 → 授予 `streak_3`「星球坚持者」→ 设备播报授勋台词；App「成长」页徽章墙点亮该徽章、连击数 +1。
- 断签演示：跳过一天再完成 → 若本月补签卡未用，自动续连并播报“我用掉一张能量修复卡帮你把连击接上了”。

### 2. 羁绊阶段升级 + 纪念日（P1 惊喜时刻）
- 让 `companionDays` 累积到 3（熟悉）/10（老朋友）/30（密友）——每天首次互动 +1 天。
- 跨过阈值后**重连设备**：约 3 秒后播报升级仪式（如“你现在是我的老朋友啦”）并授予阶段徽章。
- 陪伴天数达 7/30/100 时重连 → 播报纪念台词并授予纪念徽章。（里程碑各只庆祝一次，幂等去重）

### 3. 记忆星图（P2，收集欲 + 对话引导）
- App「档案」页顶部星图：已记录=金色点亮，可记录未填=暗，未到阶段=更暗并提示“Lv.N 解锁”。
- 关系升到熟悉/老朋友后，星图会多出「喜欢的食物 / 兴趣爱好 / 会被治愈的话题」等新星，引导对话去“喂”档案。

### 4. 能量曲线 + 星球周报 + 分享图（P2，周活 + 裂变）
- App「成长」页「近 7 天能量曲线」实时来自 `sys_energy_log`。
- 「本周星球报告」滚动展示能量起止、完成任务、连击、陪伴天数、关系阶段、本周新记住的事、专属寄语。
- 点「生成分享图」→ 客户端 canvas 生成一张品牌海报（含 AI 名字、本周数据、寄语与「来快乐星球，认领你的专属陪伴」邀请文案）→ 可保存分享。
- 每周日 21:00 定时任务把自然周报告归档到 `sys_weekly_report`（`/reports` 可回看）。

## 四、一键造演示数据（数据都是 0 时）

真实积累为空时，用内置的演示填充快速造出一份可信档案：

- **管理后台**：进入「快乐星球档案」页 → 顶部选择器选中要演示的智能体 → 点 **「填充演示数据」** →
  即刻灌入：陪伴 30 天（星球密友）、连击 8/最长 12、5 枚徽章、近 7 天能量曲线（68~90 起伏）、
  9 项档案（点亮记忆星图）、并归档一份周报。随后打开 App 同一台 AI，成长/档案/周报全部有数据。
- **或直接调接口**（Swagger / curl，需登录态）：
  `POST /api/happyplanet/demo-seed?deviceId=<设备ID>&roleId=<角色ID>`
- 幂等：可重复点击（每次清空徽章/能量流水后重灌）。仅供演示，安全边界不变。
- 若选择器为空（该设备从未对话过、还没有状态行），先让设备连一次并说句话生成状态行，或直接用上面的接口按 deviceId/roleId 填充。
- 小彩蛋：演示数据把「已庆祝阶段/纪念日」故意留在上一档，因此用真实硬件**重连一次**会现场触发
  “星球密友”升级仪式 + “相伴一月”纪念仪式（各只播一次），适合现场演示。

## 五、内容安全与调性（延续 Demo）
所有仪式/周报台词逐字入库、不过 LLM；连击“补签”而非“断签清零”，避免打卡焦虑；阶段亲密度写入动态提示词，
但硬约束仍禁止“只有我懂你”“诱导依赖”。分层档案白名单仍是代码级硬约束，敏感信息照旧拒收。
