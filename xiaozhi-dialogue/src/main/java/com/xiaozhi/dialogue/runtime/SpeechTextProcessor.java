package com.xiaozhi.dialogue.runtime;

import java.util.List;

/**
 * 朗读文本处理器：
 * <ol>
 *   <li>有状态地剥离"括号旁白/动作/表情/指令"——如 （轻声）【哽咽】[开心] &lt;e:sad&gt;。
 *       火山双向流式 TTS 会把这些原样念出来，必须去掉；跨 token 维持状态。</li>
 *   <li>从回复开头一小段推断本轮情绪（用于 audio_params.emotion 这一强情绪控制）。</li>
 * </ol>
 */
public class SpeechTextProcessor {

    /** 当前若在括号内，记录期望的闭合字符；0 表示不在括号内。 */
    private char closer = 0;

    /** 流式剥离括号旁白：跨 token 保持状态。 */
    public String strip(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (closer != 0) {
                if (c == closer) {
                    closer = 0;
                }
                // 括号内字符一律丢弃
            } else {
                char cl = closerFor(c);
                if (cl != 0) {
                    closer = cl; // 进入括号，丢弃开括号
                } else {
                    out.append(c);
                }
            }
        }
        return out.toString();
    }

    private static char closerFor(char c) {
        return switch (c) {
            case '（' -> '）';
            case '(' -> ')';
            case '[' -> ']';
            case '【' -> '】';
            case '<' -> '>';
            default -> 0;
        };
    }

    // ---------- 情绪推断 ----------

    private static final List<String> SAD = List.of("哭腔", "哽咽", "呜呜", "呜呜呜", "眼泪", "流泪", "抽泣",
            "难过", "伤心", "委屈", "泪", "别走", "舍不得");
    private static final List<String> HAPPY = List.of("哈哈", "嘻嘻", "嘿嘿", "开心", "高兴", "太好了",
            "好开心", "好棒", "耶");
    private static final List<String> ANGRY = List.of("生气", "愤怒", "气死", "哼！", "很生气", "讨厌！");
    private static final List<String> SURPRISED = List.of("天啊", "天哪", "竟然", "居然", "不会吧", "哇塞",
            "哇！", "惊", "没想到");
    private static final List<String> EXCITED = List.of("激动", "兴奋", "太棒了", "冲呀", "冲鸭", "好耶");

    /**
     * 从回复开头缓冲的一小段文本推断情绪：优先 {@code <e:xxx>} 标记，其次关键词；都没有则返回 null（交给内容自动预测）。
     */
    public static String detectEmotion(String head) {
        if (head == null || head.isEmpty()) {
            return null;
        }
        EmotionTag.Result r = EmotionTag.resolveFinal(head);
        if (r.emotion() != null) {
            return r.emotion();
        }
        if (containsAny(head, SAD)) return "sad";
        if (containsAny(head, ANGRY)) return "angry";
        if (containsAny(head, SURPRISED)) return "surprised";
        if (containsAny(head, EXCITED)) return "excited";
        if (containsAny(head, HAPPY)) return "happy";
        return null;
    }

    private static boolean containsAny(String t, List<String> words) {
        for (String w : words) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
