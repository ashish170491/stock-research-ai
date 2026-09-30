package com.ashish.stockresearch.marketdata.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RequestThrottleTest {

    private final AtomicLong now = new AtomicLong(0);
    private final List<Duration> waits = new ArrayList<>();

    private RequestThrottle throttle(double requestsPerSecond) {
        return new RequestThrottle("Yahoo Finance", requestsPerSecond, now::get, waits::add);
    }

    @Test
    void spacesBackToBackRequestsHalfASecondApartAtTwoPerSecond() throws Exception {
        RequestThrottle throttle = throttle(2);

        throttle.acquire();
        throttle.acquire();
        throttle.acquire();

        assertThat(waits).containsExactly(Duration.ofMillis(500), Duration.ofMillis(1000));
    }

    @Test
    void doesNotWaitWhenTheRequestsAreAlreadyFarEnoughApart() throws Exception {
        RequestThrottle throttle = throttle(2);

        throttle.acquire();
        now.addAndGet(Duration.ofMillis(700).toNanos());
        throttle.acquire();

        assertThat(waits).isEmpty();
    }

    @Test
    void onlyWaitsForTheRemainderOfTheInterval() throws Exception {
        RequestThrottle throttle = throttle(2);

        throttle.acquire();
        now.addAndGet(Duration.ofMillis(200).toNanos());
        throttle.acquire();

        assertThat(waits).containsExactly(Duration.ofMillis(300));
    }

    @Test
    void neverWaitsWhenTurnedOff() throws Exception {
        RequestThrottle throttle = throttle(0);

        throttle.acquire();
        throttle.acquire();

        assertThat(waits).isEmpty();
    }
}
