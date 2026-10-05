package com.jesri.email;

import com.jesri.email.config.AppProperties;
import com.jesri.email.retry.BackoffCalculator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackoffCalculatorTest {

    @Test
    void backoffIncreasesAndCaps() {
        AppProperties props = new AppProperties(
                "w1",
                2,
                "http://localhost:8000",
                new AppProperties.Streams("emails:incoming", "emails:dlq", "email-workers"),
                new AppProperties.Retry(3, 500, 2000),
                new AppProperties.Recovery(60000, 15000, 10),
                new AppProperties.Consumer(false, 10, 1000)
        );
        BackoffCalculator calc = new BackoffCalculator(props);
        long a1 = calc.delayMs(1);
        long a3 = calc.delayMs(3);
        long a10 = calc.delayMs(10);
        assertTrue(a1 >= 500 && a1 <= 2000);
        assertTrue(a3 >= a1);
        assertTrue(a10 <= 2000);
    }
}
