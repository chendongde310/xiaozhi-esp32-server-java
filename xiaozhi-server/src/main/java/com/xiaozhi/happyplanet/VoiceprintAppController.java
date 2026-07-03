package com.xiaozhi.happyplanet;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import com.xiaozhi.ai.voiceprint.VoiceprintService;
import com.xiaozhi.common.web.ApiResponse;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.UserVoiceprintDO;
import com.xiaozhi.utils.AudioUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 移动端声纹管理：创建 / 删除 / 查询「当前用户」的声纹（一人一声纹）。
 *
 * <p>录入音频由 App 端录制并降采样为 16k/16bit/单声道 WAV 上传，服务端剥离 WAV 头得到 PCM
 * 后送讯飞 createFeature。所有接口 {@code @SaCheckLogin}，userId 取自登录态。
 */
@Slf4j
@RestController
@RequestMapping("/api/app/voiceprint")
@Tag(name = "移动端 声纹", description = "用户声纹的创建、删除与状态查询")
public class VoiceprintAppController {

    @Resource
    private VoiceprintService voiceprintService;

    @GetMapping
    @SaCheckLogin
    @Operation(summary = "我的声纹状态")
    public ApiResponse<?> status() {
        Integer userId = StpUtil.getLoginIdAsInt();
        UserVoiceprintDO vp = voiceprintService.get(userId);
        return ApiResponse.success(view(vp));
    }

    @PostMapping
    @SaCheckLogin
    @Operation(summary = "创建/重录我的声纹（上传 16k 单声道 WAV）")
    public ApiResponse<?> enroll(@RequestParam("audio") MultipartFile audio) {
        Integer userId = StpUtil.getLoginIdAsInt();
        if (!voiceprintService.isEnabled()) {
            return ApiResponse.badRequest("声纹功能未启用");
        }
        if (audio == null || audio.isEmpty()) {
            return ApiResponse.badRequest("请上传录音文件");
        }
        try {
            byte[] pcm = AudioUtils.wavToPcm(audio.getBytes());
            voiceprintService.enroll(userId, pcm, null);
            return ApiResponse.success(view(voiceprintService.get(userId)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("声纹录入失败: userId={}", userId, e);
            return ApiResponse.badRequest("声纹录入失败：" + e.getMessage());
        }
    }

    @DeleteMapping
    @SaCheckLogin
    @Operation(summary = "删除我的声纹")
    public ApiResponse<?> delete() {
        Integer userId = StpUtil.getLoginIdAsInt();
        boolean removed = voiceprintService.delete(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enrolled", false);
        m.put("removed", removed);
        return ApiResponse.success(m);
    }

    private Map<String, Object> view(UserVoiceprintDO vp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", voiceprintService.isEnabled());
        m.put("enrolled", vp != null);
        m.put("threshold", voiceprintService.threshold());
        m.put("updatedAt", vp != null ? vp.getUpdateTime() : null);
        m.put("createdAt", vp != null ? vp.getCreateTime() : null);
        return m;
    }
}
