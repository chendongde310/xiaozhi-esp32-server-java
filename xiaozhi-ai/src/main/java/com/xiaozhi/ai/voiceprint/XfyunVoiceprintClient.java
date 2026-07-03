package com.xiaozhi.ai.voiceprint;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xiaozhi.ai.utils.HttpUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;

/**
 * 讯飞声纹识别（新版，服务 id s1aa729d0）底层 HTTP 客户端。
 *
 * <p>鉴权与讯飞其它 WebAPI 一致：HmacSHA256 对 {@code host\ndate\nPOST <path> HTTP/1.1} 签名，
 * 以 authorization/date/host 三个查询参数附在地址后（与 {@code XfyunSttService} 同源，仅请求行改为 POST）。
 *
 * <p>请求/响应体结构：header(app_id,status=3) + parameter.{serviceId}.{func,...} + payload.resource(音频)；
 * 响应 payload.{func}Res.text 为 base64 编码的业务 JSON，解码后才是真正结果。
 * 业务失败以 header.code!=0 表达（HTTP 可能同时为 500），本类统一抛 {@link VoiceprintApiException}。
 */
@Slf4j
@Component
public class XfyunVoiceprintClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    @Resource
    private VoiceprintProperties props;

    // ==================== 对外能力 ====================

    /** 创建声纹特征库（幂等：已存在时讯飞返回错误码，调用方可忽略）。 */
    public void createGroup() {
        JsonObject param = baseParam("createGroup");
        param.addProperty("groupName", props.getGroupId());
        param.addProperty("groupInfo", "xiaozhi voiceprint group");
        addResFormat(param, "createGroupRes");
        post(wrap(param, null), "createGroupRes");
    }

    /**
     * 添加声纹特征。若特征库不存在（错误码 23005）自动建库后重试一次。
     *
     * @param featureId 特征标识（≤32，字母数字下划线）
     * @param pcm16k    16k/16bit/单声道 raw PCM
     */
    public void createFeature(String featureId, byte[] pcm16k) {
        JsonObject param = baseParam("createFeature");
        param.addProperty("featureId", featureId);
        param.addProperty("featureInfo", "enrolled@" + Instant.now());
        addResFormat(param, "createFeatureRes");
        JsonObject body = wrap(param, pcm16k);
        try {
            post(body, "createFeatureRes");
        } catch (VoiceprintApiException e) {
            if (e.getCode() == 23005) { // 特征库不存在 → 建库后重试
                log.info("声纹特征库不存在，自动创建后重试: groupId={}", props.getGroupId());
                try {
                    createGroup();
                } catch (VoiceprintApiException ignore) {
                    // 并发/已存在等，忽略，直接重试 createFeature
                }
                post(body, "createFeatureRes");
            } else {
                throw e;
            }
        }
    }

    /**
     * 更新（覆盖）已有声纹特征——用于重录，得到干净的「替换」语义（cover=true）。
     *
     * @param featureId 已存在的特征标识
     * @param pcm16k    新的 16k/16bit/单声道 raw PCM
     */
    public void updateFeature(String featureId, byte[] pcm16k) {
        JsonObject param = baseParam("updateFeature");
        param.addProperty("featureId", featureId);
        param.addProperty("featureInfo", "reenrolled@" + Instant.now());
        param.addProperty("cover", true);
        addResFormat(param, "updateFeatureRes");
        post(wrap(param, pcm16k), "updateFeatureRes");
    }

    /** 删除声纹特征。 */
    public void deleteFeature(String featureId) {
        JsonObject param = baseParam("deleteFeature");
        param.addProperty("featureId", featureId);
        addResFormat(param, "deleteFeatureRes");
        post(wrap(param, null), "deleteFeatureRes");
    }

    /**
     * 特征比对 1:1，返回相似度得分（0~1，范围 -1~1）。
     *
     * @param dstFeatureId 目标特征标识
     * @param pcm16k       待比对音频（16k/16bit/单声道 raw PCM）
     */
    public double searchScoreFea(String dstFeatureId, byte[] pcm16k) {
        JsonObject param = baseParam("searchScoreFea");
        param.addProperty("dstFeatureId", dstFeatureId);
        addResFormat(param, "searchScoreFeaRes");
        String inner = post(wrap(param, pcm16k), "searchScoreFeaRes");
        JsonObject j = JsonParser.parseString(inner).getAsJsonObject();
        return j.has("score") ? j.get("score").getAsDouble() : -1.0;
    }

    // ==================== 请求体拼装 ====================

    private JsonObject baseParam(String func) {
        JsonObject param = new JsonObject();
        param.addProperty("func", func);
        param.addProperty("groupId", props.getGroupId());
        return param;
    }

    private void addResFormat(JsonObject param, String resKey) {
        JsonObject res = new JsonObject();
        res.addProperty("encoding", "utf8");
        res.addProperty("compress", "raw");
        res.addProperty("format", "json");
        param.add(resKey, res);
    }

    /** 组装完整请求体：header + parameter.{serviceId} + 可选 payload.resource 音频。 */
    private JsonObject wrap(JsonObject param, byte[] pcm16k) {
        JsonObject header = new JsonObject();
        header.addProperty("app_id", props.getAppId());
        header.addProperty("status", 3);

        JsonObject parameter = new JsonObject();
        parameter.add(props.getServiceId(), param);

        JsonObject body = new JsonObject();
        body.add("header", header);
        body.add("parameter", parameter);

        if (pcm16k != null) {
            JsonObject resource = new JsonObject();
            resource.addProperty("encoding", "raw");
            resource.addProperty("sample_rate", 16000);
            resource.addProperty("channels", 1);
            resource.addProperty("bit_depth", 16);
            resource.addProperty("status", 3);
            resource.addProperty("audio", Base64.getEncoder().encodeToString(pcm16k));
            JsonObject payload = new JsonObject();
            payload.add("resource", resource);
            body.add("payload", payload);
        }
        return body;
    }

    // ==================== 发送与解析 ====================

    /**
     * 发起一次请求，成功返回 payload.{resKey}.text 的 base64 解码明文（业务结果 JSON）。
     *
     * @throws VoiceprintApiException header.code!=0 时抛出（携带业务错误码）
     */
    private String post(JsonObject body, String resKey) {
        String url;
        try {
            url = assembleAuthUrl();
        } catch (Exception e) {
            throw new VoiceprintApiException(-1, "构建声纹鉴权URL失败: " + e.getMessage(), e);
        }
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = HttpUtil.client.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (raw.isEmpty()) {
                throw new VoiceprintApiException(-1, "声纹接口返回空响应 http=" + response.code());
            }
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject respHeader = json.getAsJsonObject("header");
            int code = respHeader != null && respHeader.has("code") ? respHeader.get("code").getAsInt() : -1;
            String message = respHeader != null && respHeader.has("message") ? respHeader.get("message").getAsString() : "";
            if (code != 0) {
                throw new VoiceprintApiException(code, message);
            }
            JsonObject payload = json.getAsJsonObject("payload");
            JsonObject resObj = payload != null ? payload.getAsJsonObject(resKey) : null;
            String text = resObj != null && resObj.has("text") ? resObj.get("text").getAsString() : null;
            if (text == null) {
                return "{}";
            }
            return new String(Base64.getDecoder().decode(text), StandardCharsets.UTF_8);
        } catch (VoiceprintApiException e) {
            throw e;
        } catch (Exception e) {
            throw new VoiceprintApiException(-1, "声纹接口调用失败: " + e.getMessage(), e);
        }
    }

    /** 生成带鉴权参数的完整请求地址（POST 请求行）。 */
    private String assembleAuthUrl() throws Exception {
        URL url = URI.create(props.getApiUrl()).toURL();
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = format.format(new Date());

        String signatureOrigin = "host: " + url.getHost() + "\n"
                + "date: " + date + "\n"
                + "POST " + url.getPath() + " HTTP/1.1";

        Charset charset = StandardCharsets.UTF_8;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.getApiSecret().getBytes(charset), "HmacSHA256"));
        String signature = Base64.getEncoder()
                .encodeToString(mac.doFinal(signatureOrigin.getBytes(charset)));

        String authorizationOrigin = String.format(
                "api_key=\"%s\", algorithm=\"%s\", headers=\"%s\", signature=\"%s\"",
                props.getApiKey(), "hmac-sha256", "host date request-line", signature);
        String authorization = Base64.getEncoder()
                .encodeToString(authorizationOrigin.getBytes(charset));

        return Objects.requireNonNull(HttpUrl.parse(props.getApiUrl()))
                .newBuilder()
                .addQueryParameter("authorization", authorization)
                .addQueryParameter("date", date)
                .addQueryParameter("host", url.getHost())
                .build()
                .toString();
    }

    /** 声纹接口业务异常，携带讯飞错误码（code）。 */
    public static class VoiceprintApiException extends RuntimeException {
        private final int code;

        public VoiceprintApiException(int code, String message) {
            super("[" + code + "] " + message);
            this.code = code;
        }

        public VoiceprintApiException(int code, String message, Throwable cause) {
            super("[" + code + "] " + message, cause);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
