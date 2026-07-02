package com.xiaozhi.happyplanet.service;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 快乐能量规则引擎（纯计算，无数据库、无 LLM 调用）。
 *
 * <p>"快乐能量"表达的是<b>用户当下的情绪状态与陪伴反馈</b>（对齐甲方 Demo 文档）：
 * 用户累了/情绪低 → 能量偏低；被安慰/正向表达/完成互动 → 能量回升；长期无人陪伴 → 每日衰减。
 * 能量不直接命令设备表情，而是写进动态提示词，引导 LLM 的语气与逐句表情自然一致，避免与
 * 播放器的逐句表情信号冲突。
 */
@Service
public class EnergyService {

    public static final int DEFAULT_ENERGY = 80;
    public static final int MIN_ENERGY = 0;
    public static final int MAX_ENERGY = 100;
    /** 低能量阈值：低于此值适合进入"能量修复"陪伴节奏 */
    public static final int LOW_THRESHOLD = 45;
    public static final int HIGH_THRESHOLD = 75;

    /** 每日无互动衰减与下限 */
    public static final int DECAY_PER_DAY = 5;
    public static final int DECAY_FLOOR = 30;

    /** 完成星球任务的能量奖励 */
    public static final int TASK_DONE_BONUS = 12;

    private static final List<String> NEGATIVE_WORDS = List.of(
            "累", "疲惫", "难过", "不开心", "烦", "郁闷", "焦虑", "压力", "睡不着", "失眠",
            "委屈", "孤独", "崩溃", "想哭", "难受", "撑不住", "没意思", "无聊透");
    private static final List<String> POSITIVE_WORDS = List.of(
            "谢谢", "开心", "高兴", "好多了", "好点了", "喜欢你", "太好了", "哈哈", "轻松", "舒服", "满足");
    private static final List<String> NEGATIVE_EMOTIONS = List.of(
            "sad", "angry", "crying", "shocked", "embarrassed");
    private static final List<String> POSITIVE_EMOTIONS = List.of(
            "happy", "laughing", "loving", "relaxed", "confident");

    /**
     * 根据一轮用户输入计算能量增量。
     *
     * @param userText 用户裸文本
     * @param emotion  STT 情感识别标签，可能为 null
     */
    public int deltaForTurn(String userText, String emotion) {
        String text = userText == null ? "" : userText;
        int delta = 1; // 基础：有人陪着说话，能量温和恢复

        boolean negative = containsAny(text, NEGATIVE_WORDS)
                || (StringUtils.hasText(emotion) && NEGATIVE_EMOTIONS.contains(emotion));
        boolean positive = containsAny(text, POSITIVE_WORDS)
                || (StringUtils.hasText(emotion) && POSITIVE_EMOTIONS.contains(emotion));

        if (negative) {
            delta -= 9;
        }
        if (positive) {
            delta += 7;
        }
        return delta;
    }

    public int clamp(int energy) {
        return Math.max(MIN_ENERGY, Math.min(MAX_ENERGY, energy));
    }

    public boolean isLow(int energy) {
        return energy < LOW_THRESHOLD;
    }

    /** 能量档位（供提示词与前端展示） */
    public String level(int energy) {
        if (energy < LOW_THRESHOLD) {
            return "low";
        }
        if (energy >= HIGH_THRESHOLD) {
            return "high";
        }
        return "mid";
    }

    /** 能量对应的中文心情词，供提示词引导语气 */
    public String moodWord(int energy) {
        return switch (level(energy)) {
            case "low" -> "偏低";
            case "high" -> "充沛";
            default -> "平稳";
        };
    }

    /** 能量映射到设备可用的合法表情（EmojiUtils 词表内），供需要主动下发表情的场景使用 */
    public String toEmotion(int energy) {
        return switch (level(energy)) {
            case "low" -> "sad";
            case "high" -> "happy";
            default -> "neutral";
        };
    }

    private boolean containsAny(String text, List<String> words) {
        for (String w : words) {
            if (text.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
