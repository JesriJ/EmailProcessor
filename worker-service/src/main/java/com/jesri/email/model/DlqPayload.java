package com.jesri.email.model;

import java.time.Instant;

public record DlqPayload(
        String emailId,
        Long cacheId,
        String originalRedisMessageId,
        int attemptCount,
        String failureType,
        String failureMessage,
        Instant failedAt,
        String originalPayload
) {}
