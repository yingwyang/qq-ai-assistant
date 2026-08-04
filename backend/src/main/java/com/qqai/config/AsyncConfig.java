package com.qqai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 消息异步处理线程池。
     * 注意：AI 摘要分析已迁移至 RabbitMQ（ai.analysis.queue → AiAnalysisConsumer），
     * 此线程池不再用于 AI 总结任务。仅保留供 @Deprecated processMessageAsync 及未来其他异步任务使用。
     */
    @Bean("messageTaskExecutor")
    public Executor messageTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("msg-async-");
        executor.initialize();
        return executor;
    }
}
