package com.jesri.email.service;

import com.jesri.email.config.AppProperties;
import com.jesri.email.metrics.WorkerMetrics;
import com.jesri.email.model.AiProcessRequest;
import com.jesri.email.model.AiProcessResponse;
import com.jesri.email.retry.FailureType;
import com.jesri.email.retry.ProcessingException;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Service
public class AiClient {

    private final RestTemplate restTemplate;
    private final AppProperties properties;
    private final WorkerMetrics metrics;

    public AiClient(RestTemplate restTemplate, AppProperties properties, WorkerMetrics metrics) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.metrics = metrics;
    }

    public AiProcessResponse process(AiProcessRequest request) {
        Timer.Sample sample = Timer.start();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<AiProcessResponse> response = restTemplate.postForEntity(
                    properties.aiServiceUrl() + "/process-email",
                    new HttpEntity<>(request, headers),
                    AiProcessResponse.class
            );
            AiProcessResponse body = response.getBody();
            if (body == null) {
                throw new ProcessingException(FailureType.AI_INVALID_OUTPUT, "AI response body was null");
            }
            try {
                body.validate();
            } catch (IllegalArgumentException ex) {
                throw new ProcessingException(FailureType.AI_INVALID_OUTPUT, ex.getMessage(), ex);
            }
            return body;
        } catch (HttpClientErrorException.UnprocessableEntity | HttpClientErrorException.BadRequest ex) {
            throw new ProcessingException(FailureType.AI_INVALID_OUTPUT, ex.getMessage(), ex);
        } finally {
            sample.stop(metrics.aiRequestTimer());
        }
    }
}
