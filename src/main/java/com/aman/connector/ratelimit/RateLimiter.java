package com.aman.connector.ratelimit;

import java.time.Duration;

/**
 * Thread-safe token-bucket rate limiter.
 *
 * <p>Tokens refill lazily based on elapsed time, so idle periods accumulate
 * burst capacity up to the bucket size. Callers block in {@link #acquire()}
 * until a token is available.
 */
public class RateLimiter {

    private final double permitsPerSecond;
    private final double maxTokens;
    private double availableTokens;
    private long lastRefillNanos;

    public RateLimiter(double permitsPerSecond) {
        this(permitsPerSecond, permitsPerSecond);
    }

    public RateLimiter(double permitsPerSecond, double maxBurstTokens) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive");
        }
        this.permitsPerSecond = permitsPerSecond;
        this.maxTokens = Math.max(1.0, maxBurstTokens);
        this.availableTokens = this.maxTokens;
        this.lastRefillNanos = System.nanoTime();
    }

    /** Blocks until one permit is available, then consumes it. */
    public synchronized void acquire() throws InterruptedException {
        refill();
        while (availableTokens < 1.0) {
            double deficit = 1.0 - availableTokens;
            long waitNanos = (long) (deficit / permitsPerSecond * 1_000_000_000L);
            // Cap a single wait so a stalled clock can't park a thread forever.
            waitNanos = Math.min(waitNanos, Duration.ofSeconds(60).toNanos());
            wait(waitNanos / 1_000_000L, (int) (waitNanos % 1_000_000L));
            refill();
        }
        availableTokens -= 1.0;
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSeconds > 0) {
            availableTokens = Math.min(maxTokens, availableTokens + elapsedSeconds * permitsPerSecond);
            lastRefillNanos = now;
        }
    }
}
