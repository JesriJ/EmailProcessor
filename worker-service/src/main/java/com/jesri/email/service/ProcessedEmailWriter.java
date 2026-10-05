package com.jesri.email.service;

import com.jesri.email.metrics.WorkerMetrics;
import com.jesri.email.model.AiProcessResponse;
import com.jesri.email.persistence.EmailCacheEntity;
import com.jesri.email.persistence.ProcessedEmailEntity;
import com.jesri.email.persistence.ProcessedEmailRepository;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ProcessedEmailWriter {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEmailWriter.class);

    private final ProcessedEmailRepository processedEmailRepository;
    private final WorkerMetrics metrics;

    public ProcessedEmailWriter(ProcessedEmailRepository processedEmailRepository, WorkerMetrics metrics) {
        this.processedEmailRepository = processedEmailRepository;
        this.metrics = metrics;
    }

    public boolean alreadyProcessed(String emailId) {
        return processedEmailRepository.existsById(emailId);
    }

    @Transactional
    public void saveResult(EmailCacheEntity prepared, AiProcessResponse ai, int attempt, long durationMs) {
        Timer.Sample dbSample = Timer.start();
        try {
            ProcessedEmailEntity entity = new ProcessedEmailEntity();
            entity.setEmailId(prepared.getEmailId());
            entity.setCacheId(prepared.getCacheId());
            entity.setClassification(ai.classification());
            entity.setSummary(ai.summary());
            entity.setPriority(ai.priority());
            entity.setActionRequired(ai.actionRequired());
            entity.setSuggestedAction(ai.suggestedAction());
            entity.setModel(ai.model());
            entity.setPromptVersion(ai.promptVersion());
            entity.setAttemptCount(attempt);
            entity.setProcessingDurationMs(durationMs);
            entity.setInputTokens(ai.inputTokens());
            entity.setOutputTokens(ai.outputTokens());
            entity.setProcessedAt(Instant.now());
            entity.setCreatedAt(Instant.now());
            processedEmailRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException dup) {
            log.info("Concurrent insert lost race for emailId={}; idempotent success", prepared.getEmailId());
        } finally {
            dbSample.stop(metrics.databaseWriteTimer());
        }
    }
}
