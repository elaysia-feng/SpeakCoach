// TtsProperties — 本地 GPT-SoVITS TTS 网关与音频落盘布局的配置。
// 绑定 application.yml 中的 `tts.*` 键，让 TtsServiceImpl 不用散落 @Value 注入。
package com.speakcoach.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TTS 配置项
 * <p>所有 GPT-SoVITS 与音频落盘相关的可调参数都收敛到这里，避免 TtsServiceImpl
 * 散落 {@code @Value} 注解。前缀 {@code tts.*}。</p>
 *
 * <pre>
 * tts:
 *   gpt-sovits-base-url: http://localhost:9880
 *   gpt-sovits-api-key:
 *   audio-storage-path: ./storage/audio
 *   audio-base-url: /api/audio
 *   text-language: en
 *   prompt-language: en
 *   ref-audio-path:
 *   prompt-text:
 *   speed: 1.0
 *   timeout-seconds: 30
 *   fallback-on-error: true
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "tts")
public class TtsProperties {

    /** GPT-SoVITS 推理服务的 base URL，例如 {@code http://localhost:9880}。 */
    private String gptSovitsBaseUrl = "http://localhost:9880";

    /** 若 GPT-SoVITS 部署在网关后面，需要 API Key 时填这里；空字符串表示不带鉴权头。 */
    private String gptSovitsApiKey = "";

    /** 音频落盘根目录，相对项目根目录或绝对路径都可以。 */
    private String audioStoragePath = "./storage/audio";

    /** AudioController 的 URL 前缀，用于拼接返回给前端的 audioUrl。 */
    private String audioBaseUrl = "/api/audio";

    /** TTS 合成文本语种（GPT-SoVITS 字段 text_lang）。 */
    private String textLanguage = "en";

    /** 参考语音文本语种（GPT-SoVITS 字段 prompt_lang）。 */
    private String promptLanguage = "en";

    /** 参考语音文件在 GPT-SoVITS 服务器上的绝对路径；为空则不传给后端，由后端默认值兜底。 */
    private String refAudioPath = "";

    /** 与 refAudioPath 对应的参考文本。 */
    private String promptText = "";

    /** 语速系数，1.0 为原速。 */
    private double speed = 1.0;

    /** GPT-SoVITS 调用超时（秒）。 */
    private int timeoutSeconds = 30;

    /**
     * GPT-SoVITS 不可用时是否写一个静音 WAV 占位文件并返回 URL。
     * <p>true：前端永远不会拿到 null 的 audioUrl，但播放会是几百毫秒的静音；
     * false：失败时 synthesize 返回 null，由前端决定是否降级为纯文本。</p>
     */
    private boolean fallbackOnError = true;
}
