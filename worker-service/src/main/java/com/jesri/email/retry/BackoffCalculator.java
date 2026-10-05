package com.jesri.email.retry;

import com.jesri.email.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

@Component
public class BackoffCalculator {

    private final AppProperties properties;

    public BackoffCalculator(AppProperties properties) {
        this.properties = properties;
    }

    public long delayMs(int attempt) {
        int safeAttempt = Math.max(1, attempt);
        long exponential = properties.retry().baseBackoffMs() * (1L << (safeAttempt - 1));
        long capped = Math.min(exponential, properties.retry().maxBackoffMs());
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, capped / 5));
        return Math.min(capped + jitter, properties.retry().maxBackoffMs());
    }
}
