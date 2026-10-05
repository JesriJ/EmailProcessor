package com.jesri.email.producer;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "producer")
public record ProducerProperties(String datasetPath, String streamKey, Integer limit) {}
