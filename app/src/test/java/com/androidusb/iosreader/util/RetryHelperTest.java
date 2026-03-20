package com.androidusb.iosreader.util;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class RetryHelperTest {

    @Test
    public void testSucceedsOnFirstAttempt() throws IOException {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryHelper.execute(3, attempt -> {
            attempts.incrementAndGet();
            return "success";
        });

        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    public void testSucceedsOnSecondAttempt() throws IOException {
        AtomicInteger attempts = new AtomicInteger(0);
        String result = RetryHelper.execute(3, attempt -> {
            if (attempts.incrementAndGet() < 2) {
                throw new IOException("transient failure");
            }
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(2, attempts.get());
    }

    @Test
    public void testFailsAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger(0);
        try {
            RetryHelper.execute(2, attempt -> {
                attempts.incrementAndGet();
                throw new IOException("persistent failure");
            });
            fail("Should have thrown IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("All 2 attempts failed"));
            assertEquals(2, attempts.get());
        }
    }

    @Test
    public void testAttemptNumberPassedCorrectly() throws IOException {
        AtomicInteger lastAttempt = new AtomicInteger(0);
        RetryHelper.execute(3, attempt -> {
            lastAttempt.set(attempt);
            if (attempt < 3) throw new IOException("not yet");
            return "done";
        });

        assertEquals(3, lastAttempt.get());
    }

    @Test
    public void testDefaultMaxAttempts() throws IOException {
        // Default should be 3 attempts
        AtomicInteger attempts = new AtomicInteger(0);
        try {
            RetryHelper.execute(attempt -> {
                attempts.incrementAndGet();
                throw new IOException("fail");
            });
            fail("Should have thrown");
        } catch (IOException e) {
            assertEquals(3, attempts.get());
        }
    }
}
