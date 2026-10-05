package com.jesri.email.ingest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ingest.use-mock-gmail", havingValue = "true", matchIfMissing = true)
public class MockGmailClient implements GmailClient {

    @Override
    public List<NormalizedEmail> fetchRecent(int maxMessages, int lookbackDays) {
        int n = Math.min(Math.max(maxMessages, 1), 5);
        List<NormalizedEmail> emails = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            String id = "mockmsg" + i;
            emails.add(new NormalizedEmail(
                    "gmail-" + id,
                    id,
                    "thread-" + id,
                    "Mock billing issue " + i,
                    "I was charged twice for my order #" + i,
                    "customer" + i + "@example.com",
                    Instant.now().minusSeconds(i * 3600L),
                    List.of("INBOX")
            ));
        }
        return emails;
    }
}
