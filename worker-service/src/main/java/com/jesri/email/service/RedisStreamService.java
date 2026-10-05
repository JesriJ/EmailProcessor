package com.jesri.email.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jesri.email.config.AppProperties;
import com.jesri.email.model.DlqPayload;
import com.jesri.email.model.JobPointer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class RedisStreamService {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamService.class);

    private final StringRedisTemplate redisTemplate;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;

    public RedisStreamService(
            StringRedisTemplate redisTemplate,
            AppProperties properties,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public void ensureConsumerGroup() {
        String stream = properties.streams().incoming();
        String group = properties.streams().consumerGroup();
        try {
            redisTemplate.opsForStream().createGroup(stream, ReadOffset.latest(), group);
            log.info("Created consumer group {} on {}", group, stream);
        } catch (Exception ex) {
            String message = String.valueOf(ex.getMessage());
            if (message.contains("BUSYGROUP") || message.contains("already exists")) {
                log.debug("Consumer group {} already exists on {}", group, stream);
            } else {
                // Stream may not exist yet — create with MKSTREAM semantics via XGROUP CREATE ... MKSTREAM
                try {
                    redisTemplate.getConnectionFactory()
                            .getConnection()
                            .streamCommands()
                            .xGroupCreate(
                                    stream.getBytes(),
                                    group,
                                    ReadOffset.from("0-0"),
                                    true
                            );
                    log.info("Created stream {} and consumer group {}", stream, group);
                } catch (Exception nested) {
                    String nestedMsg = String.valueOf(nested.getMessage());
                    if (!(nestedMsg.contains("BUSYGROUP") || nestedMsg.contains("already exists"))) {
                        log.warn("Unable to ensure consumer group: {}", nestedMsg);
                    }
                }
            }
        }
    }

    public String enqueue(JobPointer pointer) {
        pointer.validate();
        Map<String, String> body = new HashMap<>();
        body.put("emailId", pointer.emailId());
        body.put("cacheId", String.valueOf(pointer.cacheId()));
        RecordId id = redisTemplate.opsForStream().add(
                StreamRecords.mapBacked(body).withStreamKey(properties.streams().incoming())
        );
        return Objects.requireNonNull(id).getValue();
    }

    public List<MapRecord<String, Object, Object>> readGroup(String consumerName, int count, long blockMs) {
        StreamReadOptions options = StreamReadOptions.empty().count(count);
        if (blockMs > 0) {
            options = options.block(Duration.ofMillis(blockMs));
        }
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(properties.streams().consumerGroup(), consumerName),
                options,
                StreamOffset.create(properties.streams().incoming(), ReadOffset.lastConsumed())
        );
        return records == null ? Collections.emptyList() : records;
    }

    public void acknowledge(String messageId) {
        redisTemplate.opsForStream().acknowledge(
                properties.streams().incoming(),
                properties.streams().consumerGroup(),
                messageId
        );
    }

    public void sendToDlq(DlqPayload payload) {
        try {
            Map<String, String> body = new HashMap<>();
            body.put("emailId", payload.emailId());
            body.put("cacheId", payload.cacheId() == null ? "" : String.valueOf(payload.cacheId()));
            body.put("originalRedisMessageId", payload.originalRedisMessageId());
            body.put("attemptCount", String.valueOf(payload.attemptCount()));
            body.put("failureType", payload.failureType());
            body.put("failureMessage", payload.failureMessage());
            body.put("failedAt", payload.failedAt().toString());
            body.put("originalPayload", payload.originalPayload());
            redisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(body).withStreamKey(properties.streams().dlq())
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to write DLQ payload", ex);
        }
    }

    public List<MapRecord<String, Object, Object>> listDlq(int count) {
        var records = redisTemplate.opsForStream().range(
                properties.streams().dlq(),
                Range.unbounded()
        );
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, records.size() - count);
        return records.subList(from, records.size());
    }

    public MapRecord<String, Object, Object> findDlqByEmailId(String emailId) {
        var records = redisTemplate.opsForStream().range(
                properties.streams().dlq(),
                Range.unbounded()
        );
        if (records == null) {
            return null;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Object value = record.getValue().get("emailId");
            if (emailId.equals(String.valueOf(value))) {
                return record;
            }
        }
        return null;
    }

    public JobPointer parsePointer(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        String emailId = String.valueOf(value.get("emailId"));
        Long cacheId = Long.valueOf(String.valueOf(value.get("cacheId")));
        JobPointer pointer = new JobPointer(emailId, cacheId);
        pointer.validate();
        // Reject payloads that smuggle bodies into Redis
        if (value.containsKey("body") || value.containsKey("subject") || value.containsKey("body_text")) {
            throw new IllegalArgumentException("Redis job must be a thin pointer; body/subject fields are forbidden");
        }
        return pointer;
    }

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }

    public long streamLength(String key) {
        Long size = redisTemplate.opsForStream().size(key);
        return size == null ? 0L : size;
    }
}
