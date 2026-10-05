package com.jesri.email.ingest;

import java.time.Instant;
import java.util.List;

public record NormalizedEmail(
        String emailId,
        String providerMessageId,
        String providerThreadId,
        String subject,
        String bodyText,
        String sender,
        Instant receivedAt,
        List<String> labels
) {}
