// WebClient 配置 —— 单一具名 WebClient Bean：
//   pythonWebClient：内部 Python FastAPI 服务（/internal/turn 等）的网关。
// 该 Bean 会调整底层 Reactor Netty HttpClient 的 response timeout，
// 避免下游卡死时把发起 .block() 调用的请求线程一起冻住。
//
// 注意：之前的 `ttsWebClient` Bean（指向本地 GPT-SoVITS 推理服务器的网关）
// 已被移除，因为 Java 后端不再直接调用 GPT-SoVITS —— TTS 改为由
// Python `tts_client.synthesize` 辅助方法在一次 turn 流程中端到端完成，
// 该方法由 Python AI 网关调用。
// AudioController 仍依赖的音频落盘设置请参考 TtsProperties。
package com.speakcoach.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import io.netty.channel.ChannelOption;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TtsProperties.class)
public class WebClientConfig {

    /** Python AI 网关 URL（默认对接本地 FastAPI）。 */
    @Value("${python.service.base-url:http://localhost:9000}")
    private String pythonBaseUrl;

    /** Python 调用超时（秒）。 */
    @Value("${python.service.timeout-seconds:30}")
    private int pythonTimeoutSeconds;

    /**
     * 用于调用 Python FastAPI 服务（{@code POST /internal/turn} 等）的 Bean。
     * <p>默认使用 30 秒的 response timeout——足够覆盖 LLM + 评分的一次往返。</p>
     */
    @Bean("pythonWebClient")
    public WebClient pythonWebClient() {
        ConnectionProvider provider = ConnectionProvider.builder("python-service")
                .maxConnections(50)
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(Duration.ofSeconds(pythonTimeoutSeconds));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();

        return WebClient.builder()
                .baseUrl(pythonBaseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
