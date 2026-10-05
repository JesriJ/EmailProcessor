package com.jesri.email.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record JobPointer(String emailId, Long cacheId) {

    public void validate() {
        if (emailId == null || emailId.isBlank()) {
            throw new IllegalArgumentException("emailId is required");
        }
        if (cacheId == null || cacheId <= 0) {
            throw new IllegalArgumentException("cacheId must be a positive number");
        }
    }
}
