package com.jesri.email.consumer;

import com.jesri.email.config.AppProperties;
import com.jesri.email.model.JobPointer;
import com.jesri.email.processor.EmailProcessor;
import com.jesri.email.service.RedisStreamService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class StreamConsumerLoop {

    private static final Logger log = LoggerFactory.getLogger(StreamConsumerLoop.class);

    private final AppProperties properties;
    private final RedisStreamService redisStreamService;
    private final EmailProcessor emailProcessor;
    private final ExecutorService workerExecutor;
    private final Semaphore concurrencySemaphore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollThread;

    public StreamConsumerLoop(
            AppProperties properties,
            RedisStreamService redisStreamService,
            EmailProcessor emailProcessor,
            ExecutorService workerExecutor,
            Semaphore workerConcurrencySemaphore
    ) {
        this.properties = properties;
        this.redisStreamService = redisStreamService;
        this.emailProcessor = emailProcessor;
        this.workerExecutor = workerExecutor;
        this.concurrencySemaphore = workerConcurrencySemaphore;
    }

    @PostConstruct
    public void start() {
        if (!properties.consumer().enabled()) {
            log.info("Worker consumer disabled");
            return;
        }
        redisStreamService.ensureConsumerGroup();
        running.set(true);
        pollThread = new Thread(this::pollLoop, "stream-poller-" + properties.workerId());
        pollThread.setDaemon(true);
        pollThread.start();
        log.info(
                "Started consumer workerId={} concurrency={} group={}",
                properties.workerId(),
                properties.concurrency(),
                properties.streams().consumerGroup()
        );
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollThread != null) {
            pollThread.interrupt();
        }
    }

    private void pollLoop() {
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = redisStreamService.readGroup(
                        properties.workerId(),
                        properties.consumer().pollBatchSize(),
                        properties.consumer().blockMs()
                );
                for (MapRecord<String, Object, Object> record : records) {
                    concurrencySemaphore.acquire();
                    workerExecutor.execute(() -> {
                        try {
                            JobPointer pointer = redisStreamService.parsePointer(record);
                            emailProcessor.processRecord(pointer, record.getId().getValue(), 1);
                        } catch (Exception ex) {
                            log.error("Failed to dispatch record {}: {}", record.getId(), ex.getMessage());
                        } finally {
                            concurrencySemaphore.release();
                        }
                    });
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                String msg = String.valueOf(ex.getMessage());
                log.warn("Poll loop error: {}", msg);
                if (msg.contains("NOGROUP") || msg.contains("No such key")) {
                    redisStreamService.ensureConsumerGroup();
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
