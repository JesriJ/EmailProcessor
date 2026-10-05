package com.jesri.email.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class WorkerMetrics {

    private final Counter received;
    private final Counter processed;
    private final Counter failed;
    private final Counter retried;
    private final Counter dlq;
    private final Counter recovered;
    private final Timer processingTimer;
    private final Timer aiRequestTimer;
    private final Timer databaseWriteTimer;

    public WorkerMetrics(MeterRegistry registry) {
        this.received = registry.counter("emails_received_total");
        this.processed = registry.counter("emails_processed_total");
        this.failed = registry.counter("emails_failed_total");
        this.retried = registry.counter("emails_retried_total");
        this.dlq = registry.counter("emails_dlq_total");
        this.recovered = registry.counter("emails_recovered_total");
        this.processingTimer = registry.timer("processing_duration");
        this.aiRequestTimer = registry.timer("ai_request_duration");
        this.databaseWriteTimer = registry.timer("database_write_duration");
    }

    public void incrementReceived() {
        received.increment();
    }

    public void incrementProcessed() {
        processed.increment();
    }

    public void incrementFailed() {
        failed.increment();
    }

    public void incrementRetried() {
        retried.increment();
    }

    public void incrementDlq() {
        dlq.increment();
    }

    public void incrementRecovered() {
        recovered.increment();
    }

    public Timer processingTimer() {
        return processingTimer;
    }

    public Timer aiRequestTimer() {
        return aiRequestTimer;
    }

    public Timer databaseWriteTimer() {
        return databaseWriteTimer;
    }
}
