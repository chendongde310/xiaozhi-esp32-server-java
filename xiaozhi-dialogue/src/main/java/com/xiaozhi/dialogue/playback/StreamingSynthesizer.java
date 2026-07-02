package com.xiaozhi.dialogue.playback;

import com.xiaozhi.ai.tts.TtsService;
import com.xiaozhi.ai.tts.stream.VolcengineStreamTtsClient;
import com.xiaozhi.common.Speech;
import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.utils.AudioUtils;
import com.xiaozhi.utils.EmojiUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.slf4j.Slf4j;

/**
 * 流式合成器：基于火山豆包大模型「双向流式 TTS」，边生成边播放，避免整段文件式合成的停顿，
 * 并支持声音情绪（audio_params.emotion）。同一次回复用一个会话，音频连续无句间空白。
 *
 * <p>情绪来源由调用方通过 {@link #synthesize(Flux, String)} / {@link #synthesize(String, String)} 传入
 * （对话轮由快乐能量决定，台词场景由台词自带情绪决定）。情绪同时驱动<b>声音</b>与<b>设备表情</b>。
 */
@Slf4j
public class StreamingSynthesizer extends Synthesizer {

    private final VolcengineStreamTtsClient client;
    private final String voice;
    private final int sampleRate;

    // 支持并发多个会话（如一次仪式的多句台词各带不同情绪，Player 会顺序排队播放）
    private final Queue<VolcengineStreamTtsClient.Session> sessions = new ConcurrentLinkedQueue<>();
    private final Queue<Disposable> upstreams = new ConcurrentLinkedQueue<>();

    public StreamingSynthesizer(ChatSession chatSession, TtsService ttsService, Player player,
                                VolcengineStreamTtsClient client, String voice) {
        super(chatSession, ttsService, player);
        this.client = client;
        this.voice = voice;
        this.sampleRate = AudioUtils.SAMPLE_RATE; // 16000，与设备管线一致
    }

    @Override
    public void synthesize(String text) {
        synthesize(text, null);
    }

    @Override
    public void synthesize(Flux<String> stringFlux) {
        synthesize(stringFlux, null);
    }

    @Override
    public void synthesize(String text, String emotion) {
        synthesize(Flux.just(text == null ? "" : text), emotion);
    }

    @Override
    public void synthesize(Flux<String> stringFlux, String emotion) {
        cleanupClosed();
        String voiceEmotion = toVolcEmotion(emotion);        // 声音情绪（火山支持的枚举）
        String deviceMood = toDeviceEmotion(emotion);        // 设备表情
        // 该会话产出的音频通过 sink 交给 Player，一次回复一个连续音频流
        Sinks.Many<Speech> sink = Sinks.many().unicast().onBackpressureBuffer();
        player.play(sink.asFlux());

        // 句首文本：附加到下一块音频上，供 Player 下发 sentence_start + 表情
        AtomicReference<String> pendingSentence = new AtomicReference<>(null);
        AtomicBoolean moodSent = new AtomicBoolean(false);

        VolcengineStreamTtsClient.Session session = client.open(voice, voiceEmotion, sampleRate,
                new VolcengineStreamTtsClient.Listener() {
                    @Override
                    public void onSentenceStart(String text) {
                        if (text != null && !text.isEmpty()) {
                            pendingSentence.set(text);
                        }
                    }

                    @Override
                    public void onAudio(byte[] pcm) {
                        if (pcm == null || pcm.length == 0) {
                            return;
                        }
                        String text = pendingSentence.getAndSet(null);
                        Speech speech;
                        if (text != null) {
                            speech = new Speech(pcm, text);
                            // 每次回复的第一句附带表情，Player 会做去抖，仅在变化时下发
                            if (deviceMood != null && moodSent.compareAndSet(false, true)) {
                                speech = speech.withMood(deviceMood);
                            }
                        } else {
                            speech = new Speech(pcm);
                        }
                        sink.tryEmitNext(speech);
                    }

                    @Override
                    public void onComplete() {
                        sink.tryEmitComplete();
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("流式TTS出错 - SessionId: {}", chatSession.getSessionId(), t);
                        sink.tryEmitComplete();
                    }
                });
        sessions.add(session);

        // 把上游文本（LLM token 流 / 台词）逐段推送给 TTS 会话；剥离 emoji，避免被读出来
        Disposable upstream = stringFlux.subscribe(
                token -> {
                    String clean = stripEmoji(token);
                    if (!clean.isEmpty()) {
                        session.sendText(clean);
                    }
                },
                err -> session.finish(),
                session::finish);
        upstreams.add(upstream);
    }

    @Override
    public void cancel() {
        Disposable up;
        while ((up = upstreams.poll()) != null) {
            if (!up.isDisposed()) {
                up.dispose();
            }
        }
        VolcengineStreamTtsClient.Session s;
        while ((s = sessions.poll()) != null) {
            s.close();
        }
    }

    @Override
    public boolean isActive() {
        for (VolcengineStreamTtsClient.Session s : sessions) {
            if (!s.isClosed()) {
                return true;
            }
        }
        return false;
    }

    /** 移除已结束的会话/订阅，避免集合无限增长。 */
    private void cleanupClosed() {
        sessions.removeIf(VolcengineStreamTtsClient.Session::isClosed);
        upstreams.removeIf(Disposable::isDisposed);
    }

    /** 内部情绪 → 火山支持的声音情绪（多情感音色下生效）。 */
    private static String toVolcEmotion(String e) {
        if (e == null) {
            return null;
        }
        return switch (e) {
            case "happy", "laughing", "funny", "loving", "relaxed", "delicious", "confident", "winking", "kissy" -> "happy";
            case "sad", "crying" -> "sad";
            case "angry" -> "angry";
            case "surprised", "shocked" -> "surprised";
            default -> "neutral";
        };
    }

    /** 设备表情：情绪本身即 EmojiUtils 合法词，直接用；空则不指定。 */
    private static String toDeviceEmotion(String e) {
        return (e == null || e.isEmpty()) ? null : e;
    }

    private static String stripEmoji(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (!EmojiUtils.isEmoji(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }
}
