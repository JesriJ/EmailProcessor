package com.jesri.email.model;

public record AiProcessRequest(
        String emailId,
        String subject,
        String body,
        int attempt
) {}
