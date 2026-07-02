package com.xiaozhi.dialogue.runtime;

/**
 * LLM 回复开头的语气标记解析：约定 LLM 在每条回复最前面输出 {@code <e:xxx>}，
 * 由内容与用户的语气要求决定，服务端据此设置 TTS 声音情绪，并把标记从朗读/展示文本中剥离。
 *
 * <p>支持的情绪（对齐火山多情感音色可用集）：happy / sad / angry / surprised / excited / neutral。
 * 兼容中文与常见近义词写法。解析是流式友好的：{@link #tryResolve} 在标记未完整到达时返回 null（继续等 token）。
 */
public final class EmotionTag {

    private EmotionTag() {}

    public record Result(String emotion, String remainder) {}

    /** 尚未凑齐足以判定的字符时返回 null；否则返回解析结果（emotion 可能为 null 表示无标记）。 */
    public static Result tryResolve(String head) {
        return resolve(head, false);
    }

    /** 流结束时强制判定（不再等待更多 token）。 */
    public static Result resolveFinal(String head) {
        Result r = resolve(head, true);
        return r != null ? r : new Result(null, head);
    }

    private static Result resolve(String head, boolean isFinal) {
        if (head == null) {
            return new Result(null, "");
        }
        String h = head.stripLeading();
        if (h.isEmpty()) {
            return isFinal ? new Result(null, head) : null;
        }
        // 开头不是 '<' → 肯定没有标记，原样返回（保留原始 head）
        if (h.charAt(0) != '<') {
            return new Result(null, head);
        }
        int gt = h.indexOf('>');
        if (gt < 0) {
            // 还没收到闭合 '>'，且没超长就继续等
            if (!isFinal && h.length() <= 24) {
                return null;
            }
            return new Result(null, head);
        }
        // 任意开头的 <...> 都当作语气标记剥离，避免格式微偏时被读出来
        String tag = h.substring(1, gt);
        int colon = tag.lastIndexOf(':');
        String emo = colon >= 0 ? tag.substring(colon + 1) : tag;
        String remainder = h.substring(gt + 1);
        return new Result(normalize(emo), remainder);
    }

    /** 归一化到火山多情感音色可用的情绪；无法识别时返回 null（走内容自动预测）。 */
    public static String normalize(String e) {
        if (e == null) {
            return null;
        }
        String s = e.trim().toLowerCase();
        return switch (s) {
            case "happy", "joy", "开心", "高兴", "快乐", "愉快", "laughing", "笑" -> "happy";
            case "sad", "cry", "crying", "哭", "哭腔", "难过", "伤心", "悲伤", "委屈", "depressed", "沮丧" -> "sad";
            case "angry", "生气", "愤怒", "凶" -> "angry";
            case "surprised", "surprise", "惊讶", "吃惊", "震惊" -> "surprised";
            case "excited", "激动", "兴奋" -> "excited";
            case "neutral", "calm", "平静", "中性", "温柔", "gentle", "tender", "安慰", "comfort" -> "neutral";
            default -> null;
        };
    }
}
