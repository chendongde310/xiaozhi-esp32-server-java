package com.xiaozhi.dialogue.happyplanet;

import com.xiaozhi.communication.common.ChatSession;
import com.xiaozhi.dialogue.llm.factory.PersonaFactory;
import com.xiaozhi.dialogue.playback.Synthesizer;
import com.xiaozhi.dialogue.runtime.Persona;
import com.xiaozhi.happyplanet.dal.mysql.dataobject.ScriptLineDO;
import com.xiaozhi.happyplanet.service.ScriptLineService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 快乐星球脚本化播报器：把品牌台词库里的整句台词逐字合成播报，<b>不经过 LLM</b>，便于甲方逐字审核。
 *
 * <p>每句台词自带情绪（sys_script_lines.emotion），显式传给合成器，同时驱动<b>声音情绪</b>（火山流式大模型 TTS）
 * 与<b>设备表情</b>；一句一个合成，Player 顺序排队播放。
 */
@Slf4j
@Service
public class HappyPlanetSpeaker {

    @Resource
    private ScriptLineService scriptLineService;

    // @Lazy 打破 Bean 循环依赖：SwitchChannelFunction → HappyPlanetSpeaker → PersonaFactory
    // → toolRegistrationService → ... → GlobalFunction 列表（含 SwitchChannelFunction）。
    @Resource
    @Lazy
    private PersonaFactory personaFactory;

    /** 播报单个场景的全部台词。 */
    public void speakScene(ChatSession session, String sceneKey) {
        speakScenes(session, sceneKey);
    }

    /** 按顺序播报多个场景的台词，每句带自身情绪。 */
    public void speakScenes(ChatSession session, String... sceneKeys) {
        if (!ready(session)) {
            return;
        }
        Persona persona = personaFactory.buildPersona(session);
        if (persona == null || persona.getSynthesizer() == null) {
            log.warn("Persona/Synthesizer 不可用，跳过台词播报 - SessionId: {}", session.getSessionId());
            return;
        }
        Synthesizer syn = persona.getSynthesizer();
        int count = 0;
        for (String sceneKey : sceneKeys) {
            for (ScriptLineDO line : scriptLineService.lines(sceneKey)) {
                if (StringUtils.hasText(line.getContent())) {
                    syn.synthesize(line.getContent(), line.getEmotion());
                    count++;
                }
            }
        }
        if (count == 0) {
            log.warn("台词场景为空，跳过播报 - scenes={}", (Object) sceneKeys);
        }
    }

    /** 从某场景随机取一句台词播报（用于“多变体择一”的仪式，如授勋、补签）。 */
    public void speakOneOf(ChatSession session, String sceneKey) {
        ScriptLineDO line = scriptLineService.randomLine(sceneKey);
        if (line != null) {
            speakLine(session, line.getContent(), line.getEmotion());
        }
    }

    /** 播报一句临时台词（非台词库），带指定情绪。 */
    public void speakLine(ChatSession session, String text, String emotion) {
        if (!ready(session) || !StringUtils.hasText(text)) {
            return;
        }
        Persona persona = personaFactory.buildPersona(session);
        if (persona != null && persona.getSynthesizer() != null) {
            persona.getSynthesizer().synthesize(text, emotion);
        }
    }

    private boolean ready(ChatSession session) {
        if (session == null || !session.isAudioChannelOpen()) {
            log.warn("会话不可用或音频通道未打开，跳过台词播报");
            return false;
        }
        if (session.getDevice() == null || session.getDevice().getRoleId() == null) {
            log.warn("设备未绑定角色，跳过台词播报");
            return false;
        }
        return true;
    }
}
