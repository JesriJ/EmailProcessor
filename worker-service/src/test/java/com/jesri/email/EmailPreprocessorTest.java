package com.jesri.email;

import com.jesri.email.persistence.EmailCacheEntity;
import com.jesri.email.processor.EmailPreprocessor;
import com.jesri.email.retry.ProcessingException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmailPreprocessorTest {

    private final EmailPreprocessor preprocessor = new EmailPreprocessor();

    @Test
    void normalizesWhitespace() {
        EmailCacheEntity entity = new EmailCacheEntity();
        entity.setEmailId("synth-1");
        entity.setSubject("  Hello   world ");
        entity.setBodyText(" body ");
        entity.setSender(" a@b.com ");
        entity.setReceivedAt(Instant.now());
        EmailCacheEntity out = preprocessor.preprocess(entity);
        assertEquals("Hello world", out.getSubject());
        assertEquals("body", out.getBodyText());
        assertEquals("a@b.com", out.getSender());
    }

    @Test
    void rejectsEmptyContent() {
        EmailCacheEntity entity = new EmailCacheEntity();
        entity.setEmailId("synth-1");
        entity.setSubject(" ");
        entity.setBodyText(" ");
        entity.setSender("a@b.com");
        assertThrows(ProcessingException.class, () -> preprocessor.preprocess(entity));
    }
}
