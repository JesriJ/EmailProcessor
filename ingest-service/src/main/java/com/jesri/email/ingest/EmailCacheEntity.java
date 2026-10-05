package com.jesri.email.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "email_cache")
public class EmailCacheEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "cache_id")
    private Long cacheId;

    @Column(name = "email_id", nullable = false, unique = true)
    private String emailId;

    @Column(nullable = false)
    private String source;

    @Column(name = "provider_message_id", unique = true)
    private String providerMessageId;

    @Column(name = "provider_thread_id")
    private String providerThreadId;

    @Column(nullable = false)
    private String subject;

    @Column(name = "body_text", nullable = false, columnDefinition = "TEXT")
    private String bodyText;

    @Column(nullable = false)
    private String sender;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "labels_json")
    private String labelsJson;

    @Column(name = "ingested_at", nullable = false)
    private Instant ingestedAt;

    @Column(name = "enqueued_at")
    private Instant enqueuedAt;

    public Long getCacheId() { return cacheId; }
    public void setCacheId(Long cacheId) { this.cacheId = cacheId; }
    public String getEmailId() { return emailId; }
    public void setEmailId(String emailId) { this.emailId = emailId; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getProviderMessageId() { return providerMessageId; }
    public void setProviderMessageId(String providerMessageId) { this.providerMessageId = providerMessageId; }
    public String getProviderThreadId() { return providerThreadId; }
    public void setProviderThreadId(String providerThreadId) { this.providerThreadId = providerThreadId; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getLabelsJson() { return labelsJson; }
    public void setLabelsJson(String labelsJson) { this.labelsJson = labelsJson; }
    public Instant getIngestedAt() { return ingestedAt; }
    public void setIngestedAt(Instant ingestedAt) { this.ingestedAt = ingestedAt; }
    public Instant getEnqueuedAt() { return enqueuedAt; }
    public void setEnqueuedAt(Instant enqueuedAt) { this.enqueuedAt = enqueuedAt; }
}
