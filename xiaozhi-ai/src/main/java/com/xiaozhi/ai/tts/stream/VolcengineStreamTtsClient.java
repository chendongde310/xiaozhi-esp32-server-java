package com.xiaozhi.ai.tts.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xiaozhi.ai.utils.HttpUtil;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

/**
 * 火山引擎豆包语音大模型「双向流式 TTS」客户端（v3 二进制 WebSocket 协议）。
 *
 * <p>协议帧格式（与 {@link com.xiaozhi.ai.stt.providers.VolcengineSttService} 同源，增加 event / session_id 扩展）：
 * <pre>
 *   [4B header][4B event][ (session事件) 4B sessionId长度 + sessionId ][4B payload长度][payload]
 * </pre>
 * 事件流：StartConnection(1) → 服务端 ConnectionStarted(50)；StartSession(100,带 speaker/emotion) →
 * SessionStarted(150)；TaskRequest(200,带 text) → TTSSentenceStart(350)/音频(352)/TTSSentenceEnd(351)；
 * FinishSession(102) → SessionFinished(152)。音频以 PCM(16k/16bit/mono) 分块流式返回。
 *
 * @see <a href="https://www.volcengine.com/docs/6561/1329505">双向流式 TTS 协议</a>
 */
@Slf4j
public class VolcengineStreamTtsClient {

    private static final String WS_API_URL = "wss://openspeech.bytedance.com/api/v3/tts/bidirection";
    /** 双向流式合成默认资源 ID */
    private static final String RESOURCE_ID = "volc.service_type.10029";

    // 协议头常量
    private static final int PROTOCOL_VERSION = 0b0001;
    private static final int HEADER_SIZE = 0b0001;
    private static final int FULL_CLIENT_REQUEST = 0b0001;
    private static final int FULL_SERVER_RESPONSE = 0b1001;
    private static final int AUDIO_ONLY_RESPONSE = 0b1011;
    private static final int ERROR_RESPONSE = 0b1111;
    private static final int JSON_SERIALIZATION = 0b0001;
    private static final int NO_COMPRESSION = 0b0000;
    private static final int GZIP_COMPRESSION = 0b0001;
    private static final int FLAG_WITH_EVENT = 0b0100;

    // 上行事件
    private static final int EV_START_CONNECTION = 1;
    private static final int EV_FINISH_CONNECTION = 2;
    private static final int EV_START_SESSION = 100;
    private static final int EV_FINISH_SESSION = 102;
    private static final int EV_TASK_REQUEST = 200;

    // 下行事件
    private static final int EV_CONNECTION_FAILED = 51;
    private static final int EV_SESSION_STARTED = 150;
    private static final int EV_SESSION_FINISHED = 152;
    private static final int EV_SESSION_FAILED = 153;
    private static final int EV_TTS_SENTENCE_START = 350;
    private static final int EV_TTS_RESPONSE = 352;

    private static final String NAMESPACE = "BidirectionalTTS";

    private final String appId;
    private final String accessToken;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VolcengineStreamTtsClient(String appId, String accessToken) {
        this.appId = appId;
        this.accessToken = accessToken;
    }

    /** 音频与事件回调。所有回调可能在 WebSocket 线程触发，实现方需自行保证线程安全/快速返回。 */
    public interface Listener {
        /** 一句话开始，text 为该句原文（可用于设备端展示） */
        default void onSentenceStart(String text) {}
        /** 收到一块 PCM 音频（16kHz/16bit/mono 小端） */
        void onAudio(byte[] pcm);
        /** 整个会话正常结束 */
        void onComplete();
        /** 出错（鉴权失败/音色不支持/网络等） */
        void onError(Throwable t);
    }

    /**
     * 打开一个合成会话。连接与 StartSession 在后台完成；通过返回的 {@link Session} 推送文本。
     *
     * @param voice      音色（多情感音色如 zh_female_shuangkuaisisi_emo_v2_mars_bigtts 才能体现 emotion）
     * @param emotion    情绪（neutral/happy/sad/angry/surprised…），null 表示不指定
     * @param sampleRate 采样率，建议 16000 以匹配设备管线
     */
    public Session open(String voice, String emotion, int sampleRate, Listener listener) {
        Session session = new Session(voice, emotion, sampleRate, listener);
        session.connect();
        return session;
    }

    public class Session {
        private final String voice;
        private final String emotion;
        private final int sampleRate;
        private final Listener listener;
        private final String sessionId = UUID.randomUUID().toString();
        private final String connectId = UUID.randomUUID().toString();

        private final BlockingQueue<String> textQueue = new LinkedBlockingQueue<>();
        private final AtomicBoolean finishRequested = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicBoolean sessionStarted = new AtomicBoolean(false);
        private volatile WebSocket webSocket;
        private volatile String pendingSentence;

        Session(String voice, String emotion, int sampleRate, Listener listener) {
            this.voice = voice;
            this.emotion = emotion;
            this.sampleRate = sampleRate <= 0 ? 16000 : sampleRate;
            this.listener = listener;
        }

        void connect() {
            Request request = new Request.Builder()
                    .url(WS_API_URL)
                    .addHeader("X-Api-App-Key", appId)
                    .addHeader("X-Api-Access-Key", accessToken)
                    .addHeader("X-Api-Resource-Id", RESOURCE_ID)
                    .addHeader("X-Api-Connect-Id", connectId)
                    .build();
            HttpUtil.client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    webSocket = ws;
                    try {
                        ws.send(okio.ByteString.of(buildFrame(EV_START_CONNECTION, null, startConnectionPayload())));
                        ws.send(okio.ByteString.of(buildFrame(EV_START_SESSION, sessionId, startSessionPayload())));
                    } catch (Exception e) {
                        fail(e);
                        return;
                    }
                    // 发送线程：等 SessionStarted 后按序发送文本，最后发 FinishSession
                    Thread.startVirtualThread(this::senderLoop);
                }

                private void senderLoop() {
                    try {
                        // 等待 SessionStarted（最多 10s）
                        long deadline = System.nanoTime() + 10_000_000_000L;
                        while (!sessionStarted.get() && !closed.get() && System.nanoTime() < deadline) {
                            Thread.sleep(5);
                        }
                        if (closed.get()) return;
                        if (!sessionStarted.get()) {
                            fail(new IllegalStateException("TTS SessionStarted 超时"));
                            return;
                        }
                        while (!closed.get()) {
                            String text = textQueue.poll(50, TimeUnit.MILLISECONDS);
                            if (text != null && !text.isEmpty()) {
                                WebSocket ws = webSocket;
                                if (ws != null) {
                                    ws.send(okio.ByteString.of(buildFrame(EV_TASK_REQUEST, sessionId, taskRequestPayload(text))));
                                }
                            } else if (finishRequested.get() && textQueue.isEmpty()) {
                                WebSocket ws = webSocket;
                                if (ws != null) {
                                    ws.send(okio.ByteString.of(buildFrame(EV_FINISH_SESSION, sessionId, emptyPayload())));
                                }
                                break;
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        fail(e);
                    }
                }

                @Override
                public void onMessage(WebSocket ws, okio.ByteString bytes) {
                    try {
                        handleServerFrame(bytes.toByteArray());
                    } catch (Exception e) {
                        log.error("解析流式TTS响应失败", e);
                    }
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    fail(t);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    // 若未正常完成则视为异常结束
                    if (closed.compareAndSet(false, true)) {
                        listener.onComplete();
                    }
                }
            });
        }

        /** 推送一段文本（可多次，流式）。 */
        public void sendText(String text) {
            if (text != null && !text.isEmpty() && !closed.get()) {
                textQueue.offer(text);
            }
        }

        /** 通知不再有新文本，服务端据此完成合成。 */
        public void finish() {
            finishRequested.set(true);
        }

        /** 主动关闭（打断）。 */
        public void close() {
            if (closed.compareAndSet(false, true)) {
                WebSocket ws = webSocket;
                if (ws != null) {
                    try { ws.close(1000, "client close"); } catch (Exception ignored) {}
                }
            }
        }

        public boolean isClosed() {
            return closed.get();
        }

        private void fail(Throwable t) {
            if (closed.compareAndSet(false, true)) {
                listener.onError(t);
                WebSocket ws = webSocket;
                if (ws != null) {
                    try { ws.close(1000, "error"); } catch (Exception ignored) {}
                }
            }
        }

        private void handleServerFrame(byte[] data) throws Exception {
            if (data.length < 8) return;
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            buf.get(); // b0
            int b1 = buf.get() & 0xFF;
            int b2 = buf.get() & 0xFF;
            buf.get(); // b3 reserved
            int messageType = (b1 >> 4) & 0x0F;
            int flags = b1 & 0x0F;
            int compression = b2 & 0x0F;

            int event = -1;
            if ((flags & FLAG_WITH_EVENT) != 0 && buf.remaining() >= 4) {
                event = buf.getInt();
            }
            // session 级事件带 sessionId（连接级 50/51/52 不带）
            if (event >= 100 && buf.remaining() >= 4) {
                int sidLen = buf.getInt();
                if (sidLen > 0 && buf.remaining() >= sidLen) {
                    buf.position(buf.position() + sidLen);
                }
            }
            if (buf.remaining() < 4) return;
            int payloadLen = buf.getInt();
            if (payloadLen < 0 || buf.remaining() < payloadLen) return;
            byte[] payload = new byte[payloadLen];
            buf.get(payload);

            // 错误 / 会话失败
            if (messageType == ERROR_RESPONSE || event == EV_SESSION_FAILED || event == EV_CONNECTION_FAILED) {
                String msg = safeText(payload, compression);
                fail(new RuntimeException("火山流式TTS失败 event=" + event + " " + msg));
                return;
            }

            // 音频帧：原样 PCM，不解压
            if (messageType == AUDIO_ONLY_RESPONSE || event == EV_TTS_RESPONSE) {
                if (payload.length > 0 && !closed.get()) {
                    listener.onAudio(payload);
                }
                return;
            }

            if (event == EV_SESSION_STARTED) {
                sessionStarted.set(true);
                return;
            }
            if (event == EV_TTS_SENTENCE_START) {
                String json = safeText(payload, compression);
                try {
                    String text = objectMapper.readTree(json).path("text").asText(null);
                    if (text != null && !text.isEmpty()) {
                        listener.onSentenceStart(text);
                    }
                } catch (Exception ignored) {}
                return;
            }
            if (event == EV_SESSION_FINISHED) {
                if (closed.compareAndSet(false, true)) {
                    listener.onComplete();
                    WebSocket ws = webSocket;
                    if (ws != null) {
                        try {
                            ws.send(okio.ByteString.of(buildFrame(EV_FINISH_CONNECTION, null, emptyPayload())));
                        } catch (Exception ignored) {}
                        ws.close(1000, "done");
                    }
                }
            }
        }

        // ---------- payload 构造 ----------
        private byte[] startConnectionPayload() {
            return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        private byte[] emptyPayload() {
            return "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }

        private byte[] startSessionPayload() throws Exception {
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode user = objectMapper.createObjectNode();
            user.put("uid", "xiaozhi-" + UUID.randomUUID().toString().substring(0, 8));
            root.set("user", user);
            root.put("event", EV_START_SESSION);
            root.put("namespace", NAMESPACE);
            ObjectNode reqParams = objectMapper.createObjectNode();
            reqParams.put("speaker", voice);
            ObjectNode audioParams = objectMapper.createObjectNode();
            audioParams.put("format", "pcm");
            audioParams.put("sample_rate", sampleRate);
            if (emotion != null && !emotion.isEmpty()) {
                audioParams.put("emotion", emotion);
                audioParams.put("enable_emotion", true);
                audioParams.put("emotion_scale", 5); // 1-5，取最强，情绪更明显
            }
            reqParams.set("audio_params", audioParams);
            root.set("req_params", reqParams);
            return objectMapper.writeValueAsBytes(root);
        }

        private byte[] taskRequestPayload(String text) throws Exception {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("event", EV_TASK_REQUEST);
            root.put("namespace", NAMESPACE);
            ObjectNode reqParams = objectMapper.createObjectNode();
            reqParams.put("text", text);
            root.set("req_params", reqParams);
            return objectMapper.writeValueAsBytes(root);
        }
    }

    // ---------- 帧编解码 ----------
    private byte[] buildFrame(int event, String sessionId, byte[] payload) {
        byte[] sid = sessionId != null ? sessionId.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
        int size = 4 + 4 + (sid != null ? 4 + sid.length : 0) + 4 + payload.length;
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) ((PROTOCOL_VERSION << 4) | HEADER_SIZE));
        buf.put((byte) ((FULL_CLIENT_REQUEST << 4) | FLAG_WITH_EVENT));
        buf.put((byte) ((JSON_SERIALIZATION << 4) | NO_COMPRESSION));
        buf.put((byte) 0x00);
        buf.putInt(event);
        if (sid != null) {
            buf.putInt(sid.length);
            buf.put(sid);
        }
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    private String safeText(byte[] payload, int compression) {
        try {
            byte[] data = compression == GZIP_COMPRESSION ? gunzip(payload) : payload;
            return new String(data, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] gunzip(byte[] data) throws Exception {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(data);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPInputStream gz = new java.util.zip.GZIPInputStream(bis)) {
            byte[] b = new byte[2048];
            int n;
            while ((n = gz.read(b)) != -1) bos.write(b, 0, n);
        }
        return bos.toByteArray();
    }
}
