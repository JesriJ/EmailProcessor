package com.jesri.email.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiProcessResponse(
        String classification,
        String summary,
        String priority,
        boolean actionRequired,
        String suggestedAction,
        String model,
        String promptVersion,
        Integer inputTokens,
        Integer outputTokens
) {
    public void validate() {
        if (classification == null || classification.isBlank()) {
            throw new IllegalArgumentException("classification is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        if (priority == null || !isValidPriority(priority)) {
            throw new IllegalArgumentException("priority must be LOW|MEDIUM|HIGH|URGENT");
        }
        if (suggestedAction == null || suggestedAction.isBlank()) {
            throw new IllegalArgumentException("suggestedAction is required");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion is required");
        }
    }

    private static boolean isValidPriority(String value) {
        return switch (value) {
            case "LOW", "MEDIUM", "HIGH", "URGENT" -> true;
            default -> false;
        };
    }
}
