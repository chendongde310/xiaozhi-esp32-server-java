# 快乐星球陪伴智能体 Demo — 开发说明与演示指南

面向《快乐星球》AI 情感陪伴智能体的第一阶段内部 Demo。所有品牌台词逐字可审、内容边界写入人设，仅用于内部评估。

## 一、本次交付了什么

在现有 xiaozhi-esp32-server-java 双进程架构上，新增一套"陪伴智能体"能力，均围绕 `(deviceId, roleId)` 维度：

| 能力 | 说明 | 主要文件 |
|---|---|---|
| 人设 | "快乐星球陪伴智能体"提示词模板（温暖/轻科幻/有边界感 + 安全红线） | `V11__happy_planet.sql` 写入 `sys_template` |
| 动态状态提示词 | 每轮把"快乐能量/陪伴天数/当前频道/档案摘要"作为独立 SystemMessage 注入（稳定人设之后，KV-cache 友好） | `Conversation.dynamicStateSystemMessage`、`Persona`、`AgentRuntimeService` |
| 快乐能量 | 规则引擎：疲惫/低落→降，被安慰/正向→升，完成任务→升，跨天无互动→衰减 | `EnergyService`、`AgentStateService` |
| 长期记忆（档案） | 结构化键值档案，键受白名单约束（代码层拒绝敏感信息） | `AgentProfileService`、`sys_agent_profile` |
| 首连仪式 | 首次连接播报开场三连并引导设置昵称（原子只触发一次） | `MessageHandler.maybeFirstConnectRitual`、`HappyPlanetSpeaker` |
| 频道系统 | night/childhood/energy_repair 三频道，切换播报品牌过场台词并改变语气 | `SwitchChannelFunction` |
| 星球任务 | 每日一条轻量任务（懒加载 + 每日定时兜底），可标记完成 | `PlanetTaskService`、`CompleteTaskFunction`、`HappyPlanetTaskScheduler` |
| 管理端档案页 | "快乐星球档案"页：能量/陪伴天数/频道/任务/档案，可查看、编辑、删除 | `HappyPlanetController`、`web/.../HappyPlanetProfileView.vue` |

品牌台词全部逐字存库（`sys_script_lines`），仪式/频道过场**不经过 LLM**，便于甲方审核。LLM 只负责台词之间的自由对话，人设里写死了内容安全边界（不诊断、不给医疗建议、不诱导依赖、不说"只有我懂你"、不扮演原剧角色等）。

## 二、新增数据表（Flyway V11）

- `sys_agent_state`：能量、陪伴天数、首连标记、当前频道、今日任务。UNIQUE(deviceId, roleId)。
- `sys_agent_profile`：键值档案。UNIQUE(deviceId, roleId, fieldKey)。白名单键：nickname / preferredCall / storyType / storyLength / companionStyle / activeTime / importantDate。
- `sys_script_lines`：品牌台词库（sceneKey + seq + content + emotion）。
- `sys_planet_task`：星球任务池（category + content + audience）。

迁移仅在 `xiaozhi-server` 执行（dialogue 关闭 flyway），下次启动 server 自动建表并灌入种子数据。

## 三、如何跑通

1. 启动依赖：MySQL 8 + Redis（`docker-compose-db.yml` / `docker-compose-infra.yml`）。
2. 启动服务：`bin/all.sh start`（首次会自动执行 V11 迁移建表 + 灌台词/任务/人设模板）。
3. 在管理后台配置一个可用的 LLM（OpenAI/智谱/Ollama 等，需自备 key）与 TTS（Edge 免费或火山/MiniMax），并**新建一个角色**，把其"角色描述"设为 `sys_template` 里名为 **"快乐星球陪伴智能体"** 的模板内容；给设备绑定该角色。
   - 能量/频道/任务/档案是"角色无关"的基础设施，任何角色都会生效；但陪伴气质来自该人设，务必使用该模板。
4. 设备连接后：首连会自动播报开场仪式；之后按下面场景对话即可。

> 说明：快乐能量优先通过"动态状态提示词"影响 LLM 语气与逐句表情，不额外单独下发表情帧，避免与播放器逐句表情信号冲突；仪式/频道台词则通过给每句前置情绪 emoji 精确驱动设备表情。

## 四、三个核心演示场景与预期表现

### 场景一 · 第一次连接快乐星球（首连仪式，自动触发一次）
设备首次连接 → 约 2 秒后依次播报（表情：惊讶→开心）：
- "检测到新的地球伙伴。"
- "你好，我是来自快乐星球的陪伴智能体。以后我会陪你聊天，记录你的快乐能量，也会在你需要的时候，给你发送星球任务。"
- "在正式开始之前，我可以怎么称呼你呢？你告诉我一个名字或昵称就好。"

用户报出昵称 → LLM 调用 `update_profile(nickname, …)` 落库 → 自然回应"记住你啦，快乐星球档案建立成功"。可在管理端档案页看到 nickname。

### 场景二 · 下班后的快乐能量修复
用户说"今天好累" → 能量下降（`EnergyService` 命中"累"）→ 动态提示词提示"能量偏低，多一点安静陪伴，别急着让对方开心" → LLM 可调用 `switch_channel(energy_repair)`，播报（表情：难过）：
- "检测到快乐能量偏低。今天不用急着变开心，我先陪你慢慢恢复一点。"
- 尊重"别问太多"这类边界，回到"我会在这里陪你"。

### 场景三 · 快乐星球夜间频道
用户说"我睡不着" → LLM 调用 `switch_channel(night)`，播报（表情：困倦）：
- "收到，正在切换快乐星球夜间频道。"
- "今晚不讲大道理，也不做复杂任务。我给你讲一个很轻很短的故事，好吗？"
夜间频道下语气更轻更短。用户说"要短一点" → LLM 调 `update_profile(storyLength, short)`。结束时可播报"今天的快乐能量已经保存。晚安，地球伙伴。明天我们继续连接。"

### 第二天/重连 · 记忆与档案
再次连接不再重复首连仪式；档案（昵称、喜欢短故事、安静陪伴等）持续生效，动态提示词让智能体"记得你"。管理端"快乐星球档案"页可查看/修改/删除全部档案，回应甲方"透明可控"的要求。

## 五、内容安全边界（写入人设，硬约束）
不做心理诊断/医疗建议/人格判断/替用户做重大决定；不诱导依赖、不说"只有我懂你"；不让用户对家人隐瞒；敏感信息（住址/证件/账号/财务）在档案层**代码级白名单直接拒绝记录**；不扮演原剧角色、不编造官方设定、不复述原剧台词剧情。

## 六、涉及的 LLM 工具（Function Call，默认开启）
- `update_profile(field, value)`：写入快乐星球档案（白名单字段）。
- `switch_channel(channel)`：切换 night/childhood/energy_repair/day 频道并播报过场台词。
- `complete_task([taskId])`：标记今日星球任务完成并补充快乐能量。
