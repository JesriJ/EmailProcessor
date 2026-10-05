package com.jesri.email.producer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class SyntheticProducerRunner {

    private static final Logger log = LoggerFactory.getLogger(SyntheticProducerRunner.class);

    @Bean
    CommandLineRunner run(
            ProducerProperties properties,
            EmailCacheRepository repository,
            StringRedisTemplate redisTemplate
    ) {
        return args -> {
            Path path = Path.of(properties.datasetPath()).toAbsolutePath().normalize();
            if (!Files.exists(path)) {
                throw new IllegalStateException("Dataset not found: " + path);
            }
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            AtomicInteger published = new AtomicInteger();
            AtomicInteger skipped = new AtomicInteger();
            int limit = properties.limit() == null ? 0 : properties.limit();

            try (var lines = Files.lines(path)) {
                lines.filter(line -> !line.isBlank()).forEach(line -> {
                    if (limit > 0 && published.get() >= limit) {
                        return;
                    }
                    try {
                        SyntheticEmail email = mapper.readValue(line, SyntheticEmail.class);
                        email.validate();
                        boolean ok = persistAndEnqueue(email, repository, redisTemplate, properties.streamKey());
                        if (ok) {
                            published.incrementAndGet();
                        } else {
                            skipped.incrementAndGet();
                        }
                    } catch (Exception ex) {
                        throw new IllegalStateException("Failed on line: " + line, ex);
                    }
                });
            }

            log.info(
                    "Producer complete dataset={} published={} skippedExisting={}",
                    path,
                    published.get(),
                    skipped.get()
            );
            System.out.printf(
                    "PRODUCER_RESULT published=%d skipped=%d dataset=%s%n",
                    published.get(),
                    skipped.get(),
                    path
            );
        };
    }

    @Transactional
    boolean persistAndEnqueue(
            SyntheticEmail email,
            EmailCacheRepository repository,
            StringRedisTemplate redisTemplate,
            String streamKey
    ) {
        EmailCacheEntity entity = repository.findByEmailId(email.emailId()).orElseGet(EmailCacheEntity::new);
        boolean exists = entity.getCacheId() != null;
        entity.setEmailId(email.emailId());
        entity.setSource("synthetic");
        entity.setProviderMessageId(null);
        entity.setSubject(email.subject() == null ? "" : email.subject());
        entity.setBodyText(email.body() == null ? "" : email.body());
        entity.setSender(email.sender());
        entity.setReceivedAt(email.receivedAt());
        entity.setIngestedAt(Instant.now());
        entity = repository.saveAndFlush(entity);

        Map<String, String> body = new HashMap<>();
        body.put("emailId", entity.getEmailId());
        body.put("cacheId", String.valueOf(entity.getCacheId()));
        RecordId id = redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(body).withStreamKey(streamKey)
        );
        entity.setEnqueuedAt(Instant.now());
        repository.save(entity);
        log.info("Enqueued emailId={} cacheId={} redisId={} existing={}",
                entity.getEmailId(), entity.getCacheId(), id, exists);
        return true;
    }
}
