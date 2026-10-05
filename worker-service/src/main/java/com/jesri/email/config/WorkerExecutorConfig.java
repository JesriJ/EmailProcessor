package com.jesri.email.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class WorkerExecutorConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService workerExecutor(AppProperties properties) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "email-worker-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
        return Executors.newFixedThreadPool(Math.max(1, properties.concurrency()), factory);
    }

    @Bean
    public Semaphore workerConcurrencySemaphore(AppProperties properties) {
        return new Semaphore(Math.max(1, properties.concurrency()), true);
    }
}
