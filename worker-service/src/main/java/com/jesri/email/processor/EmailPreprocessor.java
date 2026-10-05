package com.jesri.email.processor;

import com.jesri.email.persistence.EmailCacheEntity;
import com.jesri.email.retry.FailureType;
import com.jesri.email.retry.ProcessingException;
import org.springframework.stereotype.Component;

@Component
public class EmailPreprocessor {

    private static final int MAX_BODY_LENGTH = 20_000;

    public EmailCacheEntity preprocess(EmailCacheEntity email) {
        if (email == null) {
            throw new ProcessingException(FailureType.PERMANENT, "Cached email not found");
        }
        if (email.getEmailId() == null || email.getEmailId().isBlank()) {
            throw new ProcessingException(FailureType.PERMANENT, "emailId missing in cache");
        }
        String subject = normalize(email.getSubject());
        String body = normalize(email.getBodyText());
        String sender = normalize(email.getSender());
        if (subject.isBlank() && body.isBlank()) {
            throw new ProcessingException(FailureType.PERMANENT, "subject and body are empty");
        }
        if (body.length() > MAX_BODY_LENGTH) {
            body = body.substring(0, MAX_BODY_LENGTH);
        }
        email.setSubject(subject.isBlank() ? "(no subject)" : subject);
        email.setBodyText(body);
        email.setSender(sender.isBlank() ? "unknown@example.com" : sender);
        return email;
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\u0000', ' ').trim().replaceAll("\\s+", " ");
    }
}
