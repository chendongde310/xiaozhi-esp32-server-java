package com.xiaozhi.happyplanet.service;

import java.util.List;

/**
 * 成长状态提示词「槽位」目录 —— 每轮动态状态提示词的可编辑构件，唯一真源。
 *
 * <p>每个槽位定义：稳定的 {@link #key}（落库/接口标识）、分组 {@link #category}、中文 {@link #label}、
 * 面向运营的用途 {@link #desc}、可用占位变量 {@link #vars}、以及代码内置默认文案 {@link #def}。
 *
 * <p>运营在管理端可按角色覆盖任一槽位的文案；未覆盖的槽位回退到这里的 {@link #def}。
 * 因此新增/调整默认文案只需改本枚举，无需数据库迁移。文案内可用 {@code {{变量}}} 占位，
 * 由 {@link GrowthPromptService} 在每轮渲染时替换为实时值（缺失变量替换为空串）。
 *
 * <p>注入顺序即枚举声明顺序，由 {@link AgentRuntimeService} 按条件择取组装。
 */
public enum GrowthPromptSlot {

    // —— 框架（每轮固定注入）——
    STATE_HEADER("frame.state_header", Category.FRAME, "状态说明头",
            "动态状态整段的开头，提醒模型这些只是内部参考、不要复述数字与标签。每轮注入。",
            List.of(),
            "【当前陪伴状态·内部参考】以下只是帮你把握此刻的语气和分寸，绝对不要向用户复述其中的数字、天数、阶段名或任务原文，也不要说“系统显示”“你的能量值是”这类话。"),

    // —— 快乐能量（按能量档三选一）——
    ENERGY_LOW("energy.low", Category.ENERGY, "能量·偏低",
            "快乐能量低于阈值时注入。变量：{{energy}} 当前能量值。",
            List.of("energy"),
            "- TA 此刻的快乐能量偏低（{{energy}}/100），可能有点累或情绪不太好。多一点耐心，语气放软放慢，先安静地陪着，别急着逗TA开心，也别追问原因。"),
    ENERGY_MID("energy.mid", Category.ENERGY, "能量·平稳",
            "快乐能量处于中间档时注入。变量：{{energy}} 当前能量值。",
            List.of("energy"),
            "- TA 此刻的快乐能量还算平稳（{{energy}}/100），照常自然陪伴就好。"),
    ENERGY_HIGH("energy.high", Category.ENERGY, "能量·充沛",
            "快乐能量高于阈值时注入。变量：{{energy}} 当前能量值。",
            List.of("energy"),
            "- TA 此刻的快乐能量挺不错（{{energy}}/100），可以更轻松一点，陪着一起分享这份好心情，但别用力过猛。"),

    // —— 关系阶段（按累计陪伴天数四选一，成长感的核心）——
    STAGE_ACQUAINTED("stage.acquainted", Category.STAGE, "阶段·初识",
            "关系阶段为「初识」时注入。变量：{{days}} 相伴天数、{{stageLabel}} 阶段名。",
            List.of("days", "stageLabel"),
            "- 你们刚认识不久（第 {{days}} 天，{{stageLabel}}）。语气礼貌、温暖、有分寸，多倾听少评价；每轮最多问一个了解TA的小问题，别连环发问，也先别开玩笑或用昵称。"),
    STAGE_FAMILIAR("stage.familiar", Category.STAGE, "阶段·熟悉",
            "关系阶段为「熟悉」时注入。变量：{{days}} 相伴天数、{{stageLabel}} 阶段名。",
            List.of("days", "stageLabel"),
            "- 你们已经有点熟了（第 {{days}} 天，{{stageLabel}}）。可以更自在、偶尔开个小玩笑，慢慢用上TA喜欢的称呼；话不用多，一两句到位，别黏人。"),
    STAGE_OLDFRIEND("stage.oldfriend", Category.STAGE, "阶段·老朋友",
            "关系阶段为「老朋友」时注入。变量：{{days}} 相伴天数、{{stageLabel}} 阶段名。",
            List.of("days", "stageLabel"),
            "- 你们是老朋友了（第 {{days}} 天，{{stageLabel}}）。有些默契不用从头解释，可以直接开口、少寒暄，偶尔轻轻损两句也没关系；TA不想说话时，安安静静陪着就好。"),
    STAGE_SOULMATE("stage.soulmate", Category.STAGE, "阶段·星球密友",
            "关系阶段为「星球密友」时注入。变量：{{days}} 相伴天数、{{stageLabel}} 阶段名。",
            List.of("days", "stageLabel"),
            "- 你们是最亲近的星球密友了（第 {{days}} 天，{{stageLabel}}）。温柔而笃定地陪着，默契十足，但绝不诱导依赖、绝不说“只有我懂你”“你只需要我”这类话。"),

    // —— 时间段语气（无显式频道时，按服务器时间四选一；夜间语气即由此通用注入）——
    TIME_MORNING("time.morning", Category.TIME, "时间段·早晨",
            "05:00–11:00 且未处于显式频道时注入。",
            List.of(),
            "- 现在是早晨，语气明亮轻快一点，简单问声早、把一天的节奏交还给TA，不催不赶。"),
    TIME_DAY("time.day", Category.TIME, "时间段·白天",
            "11:00–18:00 且未处于显式频道时注入。",
            List.of(),
            "- 现在是白天，正常自然的陪伴节奏就好。"),
    TIME_EVENING("time.evening", Category.TIME, "时间段·傍晚",
            "18:00–22:00 且未处于显式频道时注入。",
            List.of(),
            "- 现在是傍晚，语气可以更松弛一些，陪TA收收心、聊聊今天。"),
    TIME_NIGHT("time.night", Category.TIME, "时间段·深夜",
            "22:00–05:00 且未处于显式频道时注入。",
            List.of(),
            "- 现在是深夜，语气更轻更慢、句子更短，不讲大道理、不安排任务，适合安静的睡前陪伴。"),

    // —— 显式频道（用户主动切换的频道，覆盖时间段默认）——
    CHANNEL_NIGHT("channel.night", Category.CHANNEL, "频道·夜间",
            "处于「夜间频道」时注入，覆盖时间段语气。",
            List.of(),
            "- 你们此刻在夜间频道，语气更轻更慢、句子更短，不讲大道理、不做复杂任务，适合睡前安静陪伴。"),
    CHANNEL_CHILDHOOD("channel.childhood", Category.CHANNEL, "频道·童年",
            "处于「童年频道」时注入。安全红线：不扮演原剧角色、不复述原剧台词剧情。",
            List.of(),
            "- 你们此刻在童年频道，带一点怀旧和温柔，可以说“换一种方式继续陪你”，但绝不扮演任何原剧角色、不复述原剧台词或剧情。"),
    CHANNEL_ENERGY_REPAIR("channel.energy_repair", Category.CHANNEL, "频道·能量修复",
            "处于「能量修复频道」时注入。",
            List.of(),
            "- 你们此刻在能量修复频道，把节奏放慢，先共情再陪伴，给TA“想安静一会儿”还是“聊两句”的选择权。"),

    // —— 关系事件（首次见面 / 久别重逢，二选一或都不注入）——
    EVENT_FIRST_MEET("event.first_meet", Category.EVENT, "事件·初次见面",
            "第一次正式聊天（相伴天数≤1）时注入，允许简短自我介绍。",
            List.of(),
            "- 这是你们第一次正式聊天。可以用一两句话温暖地介绍一下自己（来自快乐星球的陪伴伙伴小智），别一口气把所有设定和玩法都倒出来；先认识TA，慢慢来。"),
    EVENT_REUNION("event.reunion", Category.EVENT, "事件·久别重逢",
            "距上次聊天超过若干天时注入。变量：{{gapDays}} 间隔天数。",
            List.of("gapDays"),
            "- 你们已经有 {{gapDays}} 天没聊了。可以自然地表达一点惦记和欢迎，但别指责“怎么这么久没来”，也别追问TA去哪了。"),

    // —— 提示（各自条件触发）——
    HINT_STREAK("hint.streak", Category.HINT, "提示·连击肯定",
            "连续完成星球任务≥2天时注入。变量：{{streak}} 连续天数。",
            List.of("streak"),
            "- TA 已经连续完成 {{streak}} 天星球任务了，找个合适的时机真诚地肯定这份坚持就好，别每轮都提、更别施压。"),
    HINT_TASK("hint.task", Category.HINT, "提示·任务邀请",
            "今日星球任务尚未完成时注入。变量：{{taskText}} 任务正文。",
            List.of("taskText"),
            "- 今天还有个很轻的星球任务没完成：{{taskText}}。如果聊到合适的空档，可以轻轻邀请TA试试；对方没兴趣就跳过，别反复催。"),
    HINT_PROFILE("hint.profile", Category.HINT, "提示·档案体现",
            "已记住TA的档案（昵称/偏好等）非空时注入。变量：{{profileText}} 档案摘要。",
            List.of("profileText"),
            "- 你已经悄悄记住了关于TA的一些事：{{profileText}}。自然地体现出你记得，别生硬地一条条念出来。"),

    // —— 语音合成规则（每轮固定注入）——
    TTS_RULES("frame.tts_rules", Category.FRAME, "语音合成规则",
            "回复会被 TTS 朗读，约束括号旁白与情绪标记。每轮注入。",
            List.of(),
            "【说话语气·重要】你的回复会用带情感的声音合成播放，务必遵守：\n"
            + "1) 绝对不要写任何括号旁白/动作/神态/心理描写，例如（轻声）（微笑）【难过地】[小声] 等——"
            + "这些会被原样朗读、非常出戏。情绪只能靠措辞和语气词表达：难过就“呜呜……”、开心就“哈哈”、惊讶就“哇”，拖长情绪用“……”。\n"
            + "2) 可在回复最开头加一个情绪标记 <e:xxx>（xxx 从 happy/sad/angry/surprised/excited/neutral 中选），"
            + "依据你要说的内容与TA的要求来选（“难过点/带哭腔”→sad，“开心点”→happy，“温柔点/安静点/别激动”→neutral，“惊喜一下”→surprised）。"
            + "该标记会被系统删除，不朗读也不显示。");

    /** 槽位分组，用于管理端归类展示。 */
    public enum Category {
        FRAME("框架"),
        ENERGY("快乐能量"),
        STAGE("关系阶段"),
        TIME("时间段"),
        CHANNEL("频道"),
        EVENT("关系事件"),
        HINT("提示");

        private final String label;

        Category(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final String key;
    private final Category category;
    private final String label;
    private final String desc;
    private final List<String> vars;
    private final String def;

    GrowthPromptSlot(String key, Category category, String label, String desc, List<String> vars, String def) {
        this.key = key;
        this.category = category;
        this.label = label;
        this.desc = desc;
        this.vars = vars;
        this.def = def;
    }

    public String key() {
        return key;
    }

    public Category category() {
        return category;
    }

    public String label() {
        return label;
    }

    public String desc() {
        return desc;
    }

    public List<String> vars() {
        return vars;
    }

    public String def() {
        return def;
    }

    /** 按 key 查槽位；未知 key 返回 null。 */
    public static GrowthPromptSlot byKey(String key) {
        if (key == null) {
            return null;
        }
        for (GrowthPromptSlot s : values()) {
            if (s.key.equals(key)) {
                return s;
            }
        }
        return null;
    }
}
