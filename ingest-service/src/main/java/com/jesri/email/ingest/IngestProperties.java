package com.jesri.email.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(
        String streamKey,
        String mailMode,
        int maxMessages,
        int lookbackDays,
        String googleClientId,
        String googleClientSecret,
        String googleRefreshToken,
        boolean useMockGmail,
        String query
) {}
