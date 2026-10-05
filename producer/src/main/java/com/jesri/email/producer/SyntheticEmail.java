package com.jesri.email.producer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SyntheticEmail(
        String emailId,
        String subject,
        String body,
        String sender,
        Instant receivedAt
) {
    public void validate() {
        if (emailId == null || emailId.isBlank()) {
            throw new IllegalArgumentException("emailId required");
        }
        if ((subject == null || subject.isBlank()) && (body == null || body.isBlank())) {
            throw new IllegalArgumentException("subject or body required for " + emailId);
        }
        if (sender == null || sender.isBlank()) {
            throw new IllegalArgumentException("sender required for " + emailId);
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt required for " + emailId);
        }
    }
}
