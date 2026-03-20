package com.androidusb.iosreader.util;

import java.io.IOException;

/**
 * Retry helper with exponential backoff for transient USB/network failures.
 */
public final class RetryHelper {
    private static final long INITIAL_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 5000;
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private RetryHelper() {}

    /**
     * Execute an operation with retry logic.
     * @param maxAttempts Maximum number of attempts (including the first)
     * @param operation The operation to execute
     * @param <T> Return type
     * @return Result of the operation
     * @throws IOException If all attempts fail
     */
    public static <T> T execute(int maxAttempts, RetryOperation<T> operation) throws IOException {
        IOException lastException = null;
        long delay = INITIAL_DELAY_MS;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return operation.run(attempt);
            } catch (IOException e) {
                lastException = e;
                Logger.w("RetryHelper", "Attempt " + attempt + "/" + maxAttempts
                        + " failed: " + e.getMessage());

                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Retry interrupted", ie);
                    }
                    delay = Math.min(delay * 2, MAX_DELAY_MS);
                }
            }
        }

        throw new IOException("All " + maxAttempts + " attempts failed", lastException);
    }

    public static <T> T execute(RetryOperation<T> operation) throws IOException {
        return execute(DEFAULT_MAX_ATTEMPTS, operation);
    }

    @FunctionalInterface
    public interface RetryOperation<T> {
        T run(int attempt) throws IOException;
    }
}
