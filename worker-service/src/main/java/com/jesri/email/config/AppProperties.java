package com.jesri.email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String workerId,
        int concurrency,
        String aiServiceUrl,
        Streams streams,
        Retry retry,
        Recovery recovery,
        Consumer consumer
) {
    public record Streams(String incoming, String dlq, String consumerGroup) {}

    public record Retry(int maxRetries, long baseBackoffMs, long maxBackoffMs) {}

    public record Recovery(long minIdleTimeMs, long claimIntervalMs, int claimBatchSize) {}

    public record Consumer(boolean enabled, int pollBatchSize, long blockMs) {}
}
