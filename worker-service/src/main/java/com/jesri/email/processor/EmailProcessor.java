package com.jesri.email.processor;

import com.jesri.email.config.AppProperties;
import com.jesri.email.metrics.WorkerMetrics;
import com.jesri.email.model.AiProcessRequest;
import com.jesri.email.model.AiProcessResponse;
import com.jesri.email.model.DlqPayload;
import com.jesri.email.model.JobPointer;
import com.jesri.email.persistence.EmailCacheEntity;
import com.jesri.email.persistence.EmailCacheRepository;
import com.jesri.email.retry.BackoffCalculator;
import com.jesri.email.retry.FailureType;
import com.jesri.email.retry.ProcessingException;
import com.jesri.email.retry.RetryClassifier;
import com.jesri.email.service.AiClient;
import com.jesri.email.service.ProcessedEmailWriter;
import com.jesri.email.service.RedisStreamService;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class EmailProcessor {

    private static final Logger log = LoggerFactory.getLogger(EmailProcessor.class);

    private final EmailCacheRepository emailCacheRepository;
    private final ProcessedEmailWriter processedEmailWriter;
    private final EmailPreprocessor preprocessor;
    private final AiClient aiClient;
    private final RedisStreamService redisStreamService;
    private final RetryClassifier retryClassifier;
    private final BackoffCalculator backoffCalculator;
    private final AppProperties properties;
    private final WorkerMetrics metrics;

    public EmailProcessor(
            EmailCacheRepository emailCacheRepository,
            ProcessedEmailWriter processedEmailWriter,
            EmailPreprocessor preprocessor,
            AiClient aiClient,
            RedisStreamService redisStreamService,
            RetryClassifier retryClassifier,
            BackoffCalculator backoffCalculator,
            AppProperties properties,
            WorkerMetrics metrics
    ) {
        this.emailCacheRepository = emailCacheRepository;
        this.processedEmailWriter = processedEmailWriter;
        this.preprocessor = preprocessor;
        this.aiClient = aiClient;
        this.redisStreamService = redisStreamService;
        this.retryClassifier = retryClassifier;
        this.backoffCalculator = backoffCalculator;
        this.properties = properties;
        this.metrics = metrics;
    }

    public void processRecord(JobPointer pointer, String redisMessageId, int startingAttempt) {
        MDC.put("workerId", properties.workerId());
        MDC.put("emailId", pointer.emailId());
        MDC.put("cacheId", String.valueOf(pointer.cacheId()));
        metrics.incrementReceived();
        Timer.Sample sample = Timer.start();
        int attempt = Math.max(1, startingAttempt);
        try {
            while (true) {
                try {
                    processOnce(pointer, attempt);
                    redisStreamService.acknowledge(redisMessageId);
                    metrics.incrementProcessed();
                    log.info("Processed email attempt={} redisMessageId={}", attempt, redisMessageId);
                    return;
                } catch (Exception ex) {
                    FailureType failureType = retryClassifier.classify(ex);
                    boolean retryable = failureType == FailureType.TRANSIENT
                            || failureType == FailureType.AI_INVALID_OUTPUT;
                    if (retryable && attempt < properties.retry().maxRetries()) {
                        metrics.incrementRetried();
                        long delay = backoffCalculator.delayMs(attempt);
                        log.warn(
                                "Transient failure type={} attempt={} backoffMs={} message={}",
                                failureType,
                                attempt,
                                delay,
                                ex.getMessage()
                        );
                        sleep(delay);
                        attempt++;
                        continue;
                    }
                    metrics.incrementFailed();
                    moveToDlq(pointer, redisMessageId, attempt, failureType, ex);
                    redisStreamService.acknowledge(redisMessageId);
                    log.error(
                            "Moved to DLQ type={} attempt={} message={}",
                            failureType,
                            attempt,
                            ex.getMessage()
                    );
                    return;
                }
            }
        } finally {
            sample.stop(metrics.processingTimer());
            MDC.clear();
        }
    }

    private void processOnce(JobPointer pointer, int attempt) {
        if (processedEmailWriter.alreadyProcessed(pointer.emailId())) {
            log.info("Idempotent skip; already processed");
            return;
        }

        EmailCacheEntity cached = emailCacheRepository.findById(pointer.cacheId())
                .orElseThrow(() -> new ProcessingException(
                        FailureType.PERMANENT,
                        "cacheId not found: " + pointer.cacheId()
                ));
        if (!pointer.emailId().equals(cached.getEmailId())) {
            throw new ProcessingException(
                    FailureType.PERMANENT,
                    "emailId/cacheId mismatch"
            );
        }

        EmailCacheEntity prepared = preprocessor.preprocess(cached);
        long started = System.nanoTime();
        AiProcessResponse ai = aiClient.process(new AiProcessRequest(
                prepared.getEmailId(),
                prepared.getSubject(),
                prepared.getBodyText(),
                attempt
        ));
        long durationMs = (System.nanoTime() - started) / 1_000_000L;
        processedEmailWriter.saveResult(prepared, ai, attempt, durationMs);
    }

    private void moveToDlq(
            JobPointer pointer,
            String redisMessageId,
            int attempt,
            FailureType failureType,
            Exception ex
    ) {
        metrics.incrementDlq();
        DlqPayload payload = new DlqPayload(
                pointer.emailId(),
                pointer.cacheId(),
                redisMessageId,
                attempt,
                failureType.name(),
                ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage(),
                Instant.now(),
                redisStreamService.toJson(pointer)
        );
        redisStreamService.sendToDlq(payload);
    }

    private void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ProcessingException(FailureType.TRANSIENT, "Interrupted during backoff", ie);
        }
    }
}
