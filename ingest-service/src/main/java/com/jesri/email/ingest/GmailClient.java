package com.jesri.email.ingest;

import java.util.List;

public interface GmailClient {
    List<NormalizedEmail> fetchRecent(int maxMessages, int lookbackDays);
}
