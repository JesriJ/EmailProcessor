package com.jesri.email;

import com.jesri.email.retry.FailureType;
import com.jesri.email.retry.ProcessingException;
import com.jesri.email.retry.RetryClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryClassifierTest {

    private final RetryClassifier classifier = new RetryClassifier();

    @Test
    void classifiesPermanentAndTransient() {
        assertEquals(FailureType.PERMANENT, classifier.classify(new IllegalArgumentException("bad")));
        assertEquals(
                FailureType.AI_INVALID_OUTPUT,
                classifier.classify(new ProcessingException(FailureType.AI_INVALID_OUTPUT, "bad schema"))
        );
        assertEquals(FailureType.TRANSIENT, classifier.classify(new ResourceAccessException("timeout")));
    }
}
