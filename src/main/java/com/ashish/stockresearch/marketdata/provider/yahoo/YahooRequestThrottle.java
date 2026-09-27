package com.ashish.stockresearch.marketdata.provider.yahoo;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * Spaces Yahoo requests at least {@code 1 / requestsPerSecond} apart, across every thread, so that
 * a report's parallel fetches - or, later, a screen over many stocks - stay under the rate at
 * which Yahoo starts refusing an unofficial client. Each request reserves the next free slot and
 * then waits for it outside the lock, so waiting requests do not block one another's bookkeeping.
 */
final class YahooRequestThrottle implements ClientHttpRequestInterceptor {

    /** How the throttle waits; a test replaces it to record the waits instead. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final long intervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;
    private final ReentrantLock lock = new ReentrantLock();
    private long nextFreeSlot = Long.MIN_VALUE;

    /** @param requestsPerSecond the maximum request rate; zero or less turns the throttle off */
    YahooRequestThrottle(double requestsPerSecond) {
        this(requestsPerSecond, System::nanoTime, duration -> Thread.sleep(duration));
    }

    YahooRequestThrottle(double requestsPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        this.intervalNanos = requestsPerSecond > 0 ? (long) (1_000_000_000L / requestsPerSecond) : 0;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        acquire();
        return execution.execute(request, body);
    }

    void acquire() throws InterruptedIOException {
        if (intervalNanos == 0) {
            return;
        }
        long wait = reserveSlot();
        if (wait > 0) {
            try {
                sleeper.sleep(Duration.ofNanos(wait));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting to call Yahoo Finance");
            }
        }
    }

    /** Takes the next free slot and returns how long to wait for it, in nanoseconds. */
    private long reserveSlot() {
        lock.lock();
        try {
            long now = nanoTime.getAsLong();
            long slot = nextFreeSlot == Long.MIN_VALUE ? now : Math.max(now, nextFreeSlot);
            nextFreeSlot = slot + intervalNanos;
            return slot - now;
        } finally {
            lock.unlock();
        }
    }
}
