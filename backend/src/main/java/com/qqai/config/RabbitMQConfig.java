package com.qqai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.util.Map;

/**
 * RabbitMQ 配置类
 *
 * 队列架构（共 5 组）：
 *  1. qqai.media     (Direct)  → media.download.queue     + 死信 media.download.dlq
 *  2. qqai.voice     (Direct)  → voice.transcode.queue    + 死信 voice.transcode.dlq
 *  3. qqai.ai        (Direct)  → ai.analysis.queue        + 死信 ai.analysis.dlq
 *  4. qqai.broadcast (Fanout)  → broadcast.queue
 *  5. qqai.digest    (Direct)  → group.digest.queue       + 死信 group.digest.queue.dlq
 *
 * 死信路由策略：业务队列消费失败 reject(requeue=false) 后，
 * 通过该队列所属的 Direct Exchange 路由到对应的 *.dlq 死信队列。
 */
@Configuration
public class RabbitMQConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQConfig.class);

    // ==================== Exchange 名称 ====================
    public static final String MEDIA_EXCHANGE = "qqai.media";
    public static final String VOICE_EXCHANGE = "qqai.voice";
    public static final String AI_EXCHANGE = "qqai.ai";
    public static final String BROADCAST_EXCHANGE = "qqai.broadcast";
    public static final String DIGEST_EXCHANGE = "qqai.digest";

    // ==================== Queue 名称 ====================
    public static final String MEDIA_DOWNLOAD_QUEUE = "media.download.queue";
    public static final String MEDIA_DOWNLOAD_DLQ = "media.download.dlq";
    public static final String VOICE_TRANSCODE_QUEUE = "voice.transcode.queue";
    public static final String VOICE_TRANSCODE_DLQ = "voice.transcode.dlq";
    public static final String AI_ANALYSIS_QUEUE = "ai.analysis.queue";
    public static final String AI_ANALYSIS_DLQ = "ai.analysis.dlq";
    public static final String BROADCAST_QUEUE = "broadcast.queue";
    /** 群日报独立队列（避免与单条摘要互相阻塞：单条摘要可能积压几十分钟） */
    public static final String GROUP_DIGEST_QUEUE = "group.digest.queue";
    public static final String GROUP_DIGEST_DLQ = "group.digest.queue.dlq";

    // ==================== Routing Key ====================
    public static final String MEDIA_DOWNLOAD_KEY = "media.download";
    public static final String MEDIA_DOWNLOAD_DLQ_KEY = "media.download.dlq";
    public static final String VOICE_TRANSCODE_KEY = "voice.transcode";
    public static final String VOICE_TRANSCODE_DLQ_KEY = "voice.transcode.dlq";
    public static final String AI_ANALYSIS_KEY = "ai.analysis";
    public static final String AI_ANALYSIS_DLQ_KEY = "ai.analysis.dlq";
    public static final String GROUP_DIGEST_KEY = "group.digest";
    public static final String GROUP_DIGEST_DLQ_KEY = "group.digest.dlq";

    // ==================== 死信参数 Key ====================
    private static final String DLX_ARG = "x-dead-letter-exchange";
    private static final String DLK_ARG = "x-dead-letter-routing-key";

    // ==================== 1. 媒体下载 ====================
    @Bean
    public DirectExchange mediaExchange() {
        return new DirectExchange(MEDIA_EXCHANGE, true, false);
    }

    @Bean
    public Queue mediaDownloadQueue() {
        return QueueBuilder.durable(MEDIA_DOWNLOAD_QUEUE)
                .withArguments(Map.of(
                        DLX_ARG, MEDIA_EXCHANGE,
                        DLK_ARG, MEDIA_DOWNLOAD_DLQ_KEY))
                .build();
    }

    @Bean
    public Queue mediaDownloadDlq() {
        return QueueBuilder.durable(MEDIA_DOWNLOAD_DLQ).build();
    }

    @Bean
    public Binding mediaDownloadBinding() {
        return BindingBuilder.bind(mediaDownloadQueue())
                .to(mediaExchange())
                .with(MEDIA_DOWNLOAD_KEY);
    }

    @Bean
    public Binding mediaDownloadDlqBinding() {
        return BindingBuilder.bind(mediaDownloadDlq())
                .to(mediaExchange())
                .with(MEDIA_DOWNLOAD_DLQ_KEY);
    }

    // ==================== 2. 语音转码 ====================
    @Bean
    public DirectExchange voiceExchange() {
        return new DirectExchange(VOICE_EXCHANGE, true, false);
    }

    @Bean
    public Queue voiceTranscodeQueue() {
        return QueueBuilder.durable(VOICE_TRANSCODE_QUEUE)
                .withArguments(Map.of(
                        DLX_ARG, VOICE_EXCHANGE,
                        DLK_ARG, VOICE_TRANSCODE_DLQ_KEY))
                .build();
    }

    @Bean
    public Queue voiceTranscodeDlq() {
        return QueueBuilder.durable(VOICE_TRANSCODE_DLQ).build();
    }

    @Bean
    public Binding voiceTranscodeBinding() {
        return BindingBuilder.bind(voiceTranscodeQueue())
                .to(voiceExchange())
                .with(VOICE_TRANSCODE_KEY);
    }

    @Bean
    public Binding voiceTranscodeDlqBinding() {
        return BindingBuilder.bind(voiceTranscodeDlq())
                .to(voiceExchange())
                .with(VOICE_TRANSCODE_DLQ_KEY);
    }

    // ==================== 3. AI 分析 ====================
    @Bean
    public DirectExchange aiExchange() {
        return new DirectExchange(AI_EXCHANGE, true, false);
    }

    @Bean
    public Queue aiAnalysisQueue() {
        return QueueBuilder.durable(AI_ANALYSIS_QUEUE)
                .withArguments(Map.of(
                        DLX_ARG, AI_EXCHANGE,
                        DLK_ARG, AI_ANALYSIS_DLQ_KEY))
                .build();
    }

    @Bean
    public Queue aiAnalysisDlq() {
        return QueueBuilder.durable(AI_ANALYSIS_DLQ).build();
    }

    @Bean
    public Binding aiAnalysisBinding() {
        return BindingBuilder.bind(aiAnalysisQueue())
                .to(aiExchange())
                .with(AI_ANALYSIS_KEY);
    }

    @Bean
    public Binding aiAnalysisDlqBinding() {
        return BindingBuilder.bind(aiAnalysisDlq())
                .to(aiExchange())
                .with(AI_ANALYSIS_DLQ_KEY);
    }

    // ==================== 4. 广播 ====================
    @Bean
    public FanoutExchange broadcastExchange() {
        return new FanoutExchange(BROADCAST_EXCHANGE, true, false);
    }

    @Bean
    public Queue broadcastQueue() {
        return QueueBuilder.durable(BROADCAST_QUEUE).build();
    }

    @Bean
    public Binding broadcastBinding() {
        return BindingBuilder.bind(broadcastQueue())
                .to(broadcastExchange());
    }

    // ==================== 5. 群日报（AI 摘要阶段 3） ====================
    @Bean
    public DirectExchange digestExchange() {
        return new DirectExchange(DIGEST_EXCHANGE, true, false);
    }

    @Bean
    public Queue groupDigestQueue() {
        return QueueBuilder.durable(GROUP_DIGEST_QUEUE)
                .withArguments(Map.of(
                        DLX_ARG, DIGEST_EXCHANGE,
                        DLK_ARG, GROUP_DIGEST_DLQ_KEY))
                .build();
    }

    @Bean
    public Queue groupDigestDlq() {
        return QueueBuilder.durable(GROUP_DIGEST_DLQ).build();
    }

    @Bean
    public Binding groupDigestBinding() {
        return BindingBuilder.bind(groupDigestQueue())
                .to(digestExchange())
                .with(GROUP_DIGEST_KEY);
    }

    @Bean
    public Binding groupDigestDlqBinding() {
        return BindingBuilder.bind(groupDigestDlq())
                .to(digestExchange())
                .with(GROUP_DIGEST_DLQ_KEY);
    }

    // ==================== 消息序列化 ====================
    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter());
        return template;
    }

    // ==================== 消费者容器工厂 ====================

    /**
     * 共享重试模板：消费者失败重试 3 次，指数退避 1s→2s→4s。
     * 全部失败后由 defaultRequeueRejected=false 触发 reject(requeue=false) → 进死信队列。
     */
    @Bean
    public RetryTemplate rabbitRetryTemplate() {
        RetryTemplate template = new RetryTemplate();
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy();
        retryPolicy.setMaxAttempts(3);
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1000L);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(10000L);
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }

    /** 媒体下载消费者容器：prefetch=5 */
    @Bean("mediaContainerFactory")
    public SimpleRabbitListenerContainerFactory mediaContainerFactory(ConnectionFactory connectionFactory) {
        return buildListenerFactory(connectionFactory, 5);
    }

    /** 语音转码消费者容器：prefetch=2（CPU 密集，限制并发） */
    @Bean("voiceContainerFactory")
    public SimpleRabbitListenerContainerFactory voiceContainerFactory(ConnectionFactory connectionFactory) {
        return buildListenerFactory(connectionFactory, 2);
    }

    /** AI 分析消费者容器：prefetch=3（I/O 密集，适度并发） */
    @Bean("aiContainerFactory")
    public SimpleRabbitListenerContainerFactory aiContainerFactory(ConnectionFactory connectionFactory) {
        return buildListenerFactory(connectionFactory, 3);
    }

    /** 群日报消费者容器：prefetch=1（单条日报要读完当天几百条消息再调大模型，耗时可达 20s+，串行执行） */
    @Bean("digestContainerFactory")
    public SimpleRabbitListenerContainerFactory digestContainerFactory(ConnectionFactory connectionFactory) {
        return buildListenerFactory(connectionFactory, 1);
    }

    private SimpleRabbitListenerContainerFactory buildListenerFactory(ConnectionFactory connectionFactory, int prefetch) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter());
        factory.setPrefetchCount(prefetch);
        // 重试耗尽后不重新入队，由队列 x-dead-letter-exchange 路由到 DLQ
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .retryOperations(rabbitRetryTemplate())
                // 重试 3 次仍失败 → reject(requeue=false) → 触发死信路由到 DLQ
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }

    /**
     * 启动后主动建立首次连接，触发 RabbitAdmin 自动声明所有 @Bean Exchange/Queue/Binding。
     *
     * spec 要求：
     *  - RabbitMQ 可用 → 启动时自动声明所有队列，日志输出连接成功
     *  - RabbitMQ 不可达 → createConnection 抛异常，应用启动失败（不静默降级）
     */
    @Bean
    public ApplicationRunner rabbitMqDeclarationRunner(ConnectionFactory connectionFactory) {
        return args -> {
            log.info("RabbitMQ 正在建立首次连接并声明所有 Exchange/Queue/Binding ...");
            connectionFactory.createConnection();
            log.info("RabbitMQ 连接成功，所有 Exchange/Queue/Binding 已声明");
        };
    }
}
