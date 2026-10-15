package com.qqai.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 出站 HTTP 客户端配置。
 *
 * <p><b>为什么必须有超时</b>：{@code new RestTemplate()} / {@code HttpClients.createDefault()} 默认
 * <b>没有连接与读取超时</b>。下游（AstrBot / NapCat / GPT-SoVITS）一旦卡住不返回，调用线程会被无限期占用，
 * 并发上来就把 Tomcat 线程池占满——表现为整个服务无响应。所有出站调用都必须带超时。</p>
 *
 * <p>按耗时特征分三类（超时均可配置覆盖）：</p>
 * <ul>
 *   <li>{@link #httpClient()} —— 轻量内部调用（NapCat 等），快；</li>
 *   <li>{@link #restTemplate()} —— AstrBot 聊天/分析（大模型生成，慢但有限）；</li>
 *   <li>{@link #longRunningHttpClient()} —— GPT-SoVITS 语音合成等重推理，需要更长等待。</li>
 * </ul>
 */
@Configuration
public class HttpClientConfig {

    /** 连接超时（毫秒）：连不上就该尽快失败，不必等 */
    @Value("${app.http.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    /** 轻量内部调用的读取超时（毫秒） */
    @Value("${app.http.response-timeout-ms:60000}")
    private int responseTimeoutMs;

    /** 大模型 / 语音合成等长耗时调用的超时（毫秒） */
    @Value("${app.http.long-timeout-ms:300000}")
    private int longTimeoutMs;

    /** 轻量内部调用客户端（NapCat 等）。@Primary 保证按类型注入处仍拿到它 */
    @Bean(destroyMethod = "close")
    @Primary
    public CloseableHttpClient httpClient() {
        return buildClient(responseTimeoutMs);
    }

    /** 长耗时推理客户端（GPT-SoVITS 合成等）：等待更宽但仍有上界 */
    @Bean(name = "longRunningHttpClient", destroyMethod = "close")
    public CloseableHttpClient longRunningHttpClient() {
        return buildClient(longTimeoutMs);
    }

    /**
     * 共享的 RestTemplate：带连接/读取超时，供 AstrBot 等大模型调用使用。
     * 原先各处 {@code new RestTemplate()} 无超时，下游卡死会一直挂住请求线程。
     */
    @Bean
    public RestTemplate restTemplate() {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(longTimeoutMs))
                .build();
        CloseableHttpClient client = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
        ClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(client);
        return new RestTemplate(factory);
    }

    private CloseableHttpClient buildClient(int responseTimeoutMillis) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
                .setResponseTimeout(Timeout.ofMilliseconds(responseTimeoutMillis))
                .build();

        return HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }
}
