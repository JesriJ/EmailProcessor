package com.jesri.email.retry;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

@Component
public class RetryClassifier {

    public FailureType classify(Throwable error) {
        if (error instanceof ProcessingException pe) {
            return pe.getFailureType();
        }
        if (error instanceof IllegalArgumentException) {
            return FailureType.PERMANENT;
        }
        if (error instanceof HttpClientErrorException.TooManyRequests) {
            return FailureType.TRANSIENT;
        }
        if (error instanceof HttpClientErrorException clientError) {
            int status = clientError.getStatusCode().value();
            if (status == 408 || status == 429) {
                return FailureType.TRANSIENT;
            }
            return FailureType.PERMANENT;
        }
        if (error instanceof HttpServerErrorException
                || error instanceof ResourceAccessException
                || error instanceof TransientDataAccessException
                || error instanceof DataAccessResourceFailureException) {
            return FailureType.TRANSIENT;
        }
        Throwable cause = error.getCause();
        if (cause != null && cause != error) {
            return classify(cause);
        }
        return FailureType.TRANSIENT;
    }
}
