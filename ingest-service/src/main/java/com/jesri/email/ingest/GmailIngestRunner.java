package com.jesri.email.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Configuration
public class GmailIngestRunner {

    private static final Logger log = LoggerFactory.getLogger(GmailIngestRunner.class);

    @Bean
    CommandLineRunner run(
            IngestProperties properties,
            GmailClient gmailClient,
            EmailCacheRepository repository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper
    ) {
        return args -> {
            if (!"gmail".equalsIgnoreCase(properties.mailMode())
                    && !properties.useMockGmail()) {
                log.info("MAIL_MODE={} and mock disabled; ingest no-op", properties.mailMode());
                return;
            }
            List<NormalizedEmail> emails = gmailClient.fetchRecent(
                    properties.maxMessages(),
                    properties.lookbackDays()
            );
            int cached = 0;
            int enqueued = 0;
            for (NormalizedEmail email : emails) {
                EmailCacheEntity entity = repository.findByProviderMessageId(email.providerMessageId())
                        .or(() -> repository.findByEmailId(email.emailId()))
                        .orElseGet(EmailCacheEntity::new);
                boolean newlyCached = entity.getCacheId() == null;
                entity.setEmailId(email.emailId());
                entity.setSource("gmail");
                entity.setProviderMessageId(email.providerMessageId());
                entity.setProviderThreadId(email.providerThreadId());
                entity.setSubject(email.subject() == null ? "" : email.subject());
                entity.setBodyText(email.bodyText() == null ? "" : email.bodyText());
                entity.setSender(email.sender() == null ? "unknown@example.com" : email.sender());
                entity.setReceivedAt(email.receivedAt());
                entity.setLabelsJson(objectMapper.writeValueAsString(email.labels()));
                if (entity.getIngestedAt() == null) {
                    entity.setIngestedAt(Instant.now());
                }
                entity = repository.saveAndFlush(entity);
                if (newlyCached) {
                    cached++;
                }
                if (entity.getEnqueuedAt() == null) {
                    Map<String, String> body = new HashMap<>();
                    body.put("emailId", entity.getEmailId());
                    body.put("cacheId", String.valueOf(entity.getCacheId()));
                    RecordId id = redisTemplate.opsForStream().add(
                            StreamRecords.mapBacked(body).withStreamKey(properties.streamKey())
                    );
                    entity.setEnqueuedAt(Instant.now());
                    repository.save(entity);
                    enqueued++;
                    log.info("Gmail enqueued emailId={} cacheId={} redisId={}",
                            entity.getEmailId(), entity.getCacheId(), id);
                }
            }
            System.out.printf("INGEST_RESULT fetched=%d newlyCached=%d enqueued=%d%n",
                    emails.size(), cached, enqueued);
        };
    }

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
