package com.jesri.email.admin;

import com.jesri.email.model.JobPointer;
import com.jesri.email.persistence.EmailCacheEntity;
import com.jesri.email.persistence.EmailCacheRepository;
import com.jesri.email.persistence.ProcessedEmailEntity;
import com.jesri.email.persistence.ProcessedEmailRepository;
import com.jesri.email.service.RedisStreamService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final RedisStreamService redisStreamService;
    private final EmailCacheRepository emailCacheRepository;
    private final ProcessedEmailRepository processedEmailRepository;

    public AdminController(
            RedisStreamService redisStreamService,
            EmailCacheRepository emailCacheRepository,
            ProcessedEmailRepository processedEmailRepository
    ) {
        this.redisStreamService = redisStreamService;
        this.emailCacheRepository = emailCacheRepository;
        this.processedEmailRepository = processedEmailRepository;
    }

    @GetMapping("/dlq")
    public List<Map<String, Object>> dlqList(@RequestParam(defaultValue = "50") int limit) {
        List<MapRecord<String, Object, Object>> records = redisStreamService.listDlq(limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("redisId", record.getId().getValue());
            record.getValue().forEach((k, v) -> row.put(String.valueOf(k), v));
            result.add(row);
        }
        return result;
    }

    @GetMapping("/dlq/{emailId}")
    public ResponseEntity<Map<String, Object>> dlqInspect(@PathVariable String emailId) {
        MapRecord<String, Object, Object> record = redisStreamService.findDlqByEmailId(emailId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("redisId", record.getId().getValue());
        record.getValue().forEach((k, v) -> row.put(String.valueOf(k), v));
        return ResponseEntity.ok(row);
    }

    @PostMapping("/dlq/{emailId}/replay")
    public ResponseEntity<Map<String, Object>> dlqReplay(@PathVariable String emailId) {
        MapRecord<String, Object, Object> record = redisStreamService.findDlqByEmailId(emailId);
        if (record == null) {
            return ResponseEntity.notFound().build();
        }
        String cacheIdRaw = String.valueOf(record.getValue().get("cacheId"));
        JobPointer pointer = new JobPointer(emailId, Long.valueOf(cacheIdRaw));
        String newId = redisStreamService.enqueue(pointer);
        return ResponseEntity.ok(Map.of(
                "emailId", emailId,
                "cacheId", pointer.cacheId(),
                "newRedisMessageId", newId
        ));
    }

    @PostMapping("/reprocess")
    public Map<String, Object> reprocessRange(
            @RequestParam long fromCacheId,
            @RequestParam long toCacheId
    ) {
        List<EmailCacheEntity> rows = emailCacheRepository.findByCacheIdRange(fromCacheId, toCacheId);
        int enqueued = 0;
        for (EmailCacheEntity row : rows) {
            redisStreamService.enqueue(new JobPointer(row.getEmailId(), row.getCacheId()));
            enqueued++;
        }
        return Map.of(
                "fromCacheId", fromCacheId,
                "toCacheId", toCacheId,
                "enqueued", enqueued
        );
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Long> bySource = new LinkedHashMap<>();
        for (Object[] row : emailCacheRepository.countGroupedBySource()) {
            bySource.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> byClassification = new LinkedHashMap<>();
        for (Object[] row : processedEmailRepository.countGroupedByClassification()) {
            byClassification.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (Object[] row : processedEmailRepository.countGroupedByPriority()) {
            byPriority.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cached", emailCacheRepository.count());
        out.put("processed", processedEmailRepository.count());
        out.put("enqueued", emailCacheRepository.countByEnqueuedAtIsNotNull());
        out.put("gmailCached", emailCacheRepository.countBySource("gmail"));
        out.put("syntheticCached", emailCacheRepository.countBySource("synthetic"));
        out.put("bySource", bySource);
        out.put("byClassification", byClassification);
        out.put("byPriority", byPriority);
        out.put("incomingStreamLength", redisStreamService.streamLength("emails:incoming"));
        out.put("dlqStreamLength", redisStreamService.streamLength("emails:dlq"));
        return out;
    }

    @GetMapping("/recent")
    public List<Map<String, Object>> recent() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProcessedEmailEntity p : processedEmailRepository.findTop25ByOrderByProcessedAtDesc()) {
            rows.add(toEmailCard(p, emailCacheRepository.findById(p.getCacheId()).orElse(null), false));
        }
        return rows;
    }

    @GetMapping("/emails")
    public Map<String, Object> listEmails(
            @RequestParam(required = false) String classification,
            @RequestParam(required = false) String priority,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int pageSize = Math.min(Math.max(size, 1), 200);
        Page<ProcessedEmailEntity> result = processedEmailRepository.search(
                blankToNull(classification),
                blankToNull(priority),
                blankToNull(q),
                PageRequest.of(Math.max(page, 0), pageSize, Sort.by(Sort.Direction.DESC, "processedAt"))
        );
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProcessedEmailEntity p : result.getContent()) {
            items.add(toEmailCard(p, emailCacheRepository.findById(p.getCacheId()).orElse(null), false));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("page", result.getNumber());
        out.put("size", result.getSize());
        out.put("totalItems", result.getTotalElements());
        out.put("totalPages", result.getTotalPages());
        out.put("items", items);
        return out;
    }

    @GetMapping("/emails/{emailId}")
    public ResponseEntity<Map<String, Object>> getEmail(@PathVariable String emailId) {
        Optional<ProcessedEmailEntity> processed = processedEmailRepository.findByEmailId(emailId);
        if (processed.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ProcessedEmailEntity p = processed.get();
        EmailCacheEntity cache = emailCacheRepository.findById(p.getCacheId()).orElse(null);
        return ResponseEntity.ok(toEmailCard(p, cache, true));
    }

    private Map<String, Object> toEmailCard(ProcessedEmailEntity p, EmailCacheEntity cache, boolean includeBody) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("emailId", p.getEmailId());
        row.put("cacheId", p.getCacheId());
        row.put("classification", p.getClassification());
        row.put("priority", p.getPriority());
        row.put("summary", p.getSummary());
        row.put("actionRequired", p.isActionRequired());
        row.put("suggestedAction", p.getSuggestedAction());
        row.put("model", p.getModel());
        row.put("processedAt", p.getProcessedAt());
        row.put("processingDurationMs", p.getProcessingDurationMs());
        if (cache != null) {
            row.put("source", cache.getSource());
            row.put("subject", cache.getSubject());
            row.put("sender", cache.getSender());
            row.put("receivedAt", cache.getReceivedAt());
            row.put("providerMessageId", cache.getProviderMessageId());
            row.put("providerThreadId", cache.getProviderThreadId());
            if (includeBody) {
                row.put("bodyText", cache.getBodyText());
            } else {
                String body = cache.getBodyText() == null ? "" : cache.getBodyText();
                row.put("bodyPreview", body.length() > 220 ? body.substring(0, 220) + "…" : body);
            }
            String gmailUrl = GmailLinks.openInGmail(
                    cache.getSource(),
                    cache.getProviderThreadId(),
                    cache.getProviderMessageId()
            );
            String mailto = GmailLinks.mailtoReply(cache.getSender(), cache.getSubject());
            row.put("gmailUrl", gmailUrl);
            row.put("mailtoUrl", mailto);
            row.put("canOpenInGmail", gmailUrl != null);
        } else {
            row.put("source", null);
            row.put("subject", null);
            row.put("sender", null);
            row.put("gmailUrl", null);
            row.put("mailtoUrl", null);
            row.put("canOpenInGmail", false);
        }
        return row;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) {
            return null;
        }
        return value.trim();
    }
}
