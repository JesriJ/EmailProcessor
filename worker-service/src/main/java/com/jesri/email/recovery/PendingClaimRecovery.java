package com.jesri.email.recovery;

import com.jesri.email.config.AppProperties;
import com.jesri.email.metrics.WorkerMetrics;
import com.jesri.email.model.JobPointer;
import com.jesri.email.processor.EmailProcessor;
import com.jesri.email.service.RedisStreamService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Component
public class PendingClaimRecovery {

    private static final Logger log = LoggerFactory.getLogger(PendingClaimRecovery.class);

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;
    private final EmailProcessor emailProcessor;
    private final RedisStreamService redisStreamService;
    private final WorkerMetrics metrics;

    public PendingClaimRecovery(
            StringRedisTemplate redisTemplate,
            AppProperties properties,
            EmailProcessor emailProcessor,
            RedisStreamService redisStreamService,
            WorkerMetrics metrics
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.emailProcessor = emailProcessor;
        this.redisStreamService = redisStreamService;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${app.recovery.claim-interval-ms:15000}")
    public void claimStaleMessages() {
        if (!properties.consumer().enabled()) {
            return;
        }
        try {
            PendingMessages pending = redisTemplate.opsForStream().pending(
                    properties.streams().incoming(),
                    properties.streams().consumerGroup(),
                    org.springframework.data.domain.Range.unbounded(),
                    properties.recovery().claimBatchSize()
            );
            if (pending == null || pending.isEmpty()) {
                return;
            }
            RecordId[] ids = pending.stream()
                    .filter(pm -> pm.getElapsedTimeSinceLastDelivery()
                            .compareTo(Duration.ofMillis(properties.recovery().minIdleTimeMs())) >= 0)
                    .map(PendingMessage::getId)
                    .toArray(RecordId[]::new);
            if (ids.length == 0) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream().claim(
                    properties.streams().incoming(),
                    properties.streams().consumerGroup(),
                    properties.workerId(),
                    Duration.ofMillis(properties.recovery().minIdleTimeMs()),
                    ids
            );
            if (claimed == null || claimed.isEmpty()) {
                return;
            }
            for (MapRecord<String, Object, Object> record : claimed) {
                metrics.incrementRecovered();
                JobPointer pointer = redisStreamService.parsePointer(record);
                log.warn(
                        "Recovered pending message redisMessageId={} emailId={} via XCLAIM",
                        Objects.requireNonNull(record.getId()).getValue(),
                        pointer.emailId()
                );
                emailProcessor.processRecord(pointer, record.getId().getValue(), 1);
            }
        } catch (Exception ex) {
            log.debug("Pending claim cycle skipped: {}", ex.getMessage());
        }
    }
}
