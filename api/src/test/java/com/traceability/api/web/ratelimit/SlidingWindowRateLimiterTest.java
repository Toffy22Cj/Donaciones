package com.traceability.api.web.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** ADR-053 D2 y D5: ventana deslizante exacta, concurrencia por clave y memoria acotada. */
class SlidingWindowRateLimiterTest {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final MutableClock clock = new MutableClock();
    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(clock, 1_000_000);

    @Test
    void acceptsTheLimitAndRejectsTheNextWithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, WINDOW).allowed()).as("attempt %d", i + 1).isTrue();
            clock.advance(Duration.ofSeconds(1));
        }
        SlidingWindowRateLimiter.Decision sixth = limiter.tryAcquire("k", 5, WINDOW);
        assertThat(sixth.allowed()).isFalse();
        // la primera aceptada fue hace 5 s: sale de la ventana dentro de 55 s
        assertThat(sixth.retryAfterSeconds()).isEqualTo(55);
    }

    @Test
    void theWindowSlidesAndAcceptsAgainExactlyWhenTheOldestLeaves() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("k", 3, WINDOW);
        }
        clock.advance(WINDOW.minusMillis(1));
        assertThat(limiter.tryAcquire("k", 3, WINDOW).allowed()).isFalse();
        clock.advance(Duration.ofMillis(1));
        assertThat(limiter.tryAcquire("k", 3, WINDOW).allowed()).isTrue();
        // entraron 3 a la vez: las 3 salen a la vez, así que caben 2 más y la siguiente no
        assertThat(limiter.tryAcquire("k", 3, WINDOW).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 3, WINDOW).allowed()).isTrue();
        assertThat(limiter.tryAcquire("k", 3, WINDOW).allowed()).isFalse();
    }

    @Test
    void rejectedAttemptsDoNotExtendTheWindow() {
        limiter.tryAcquire("k", 1, WINDOW);
        for (int i = 0; i < 10; i++) {
            clock.advance(Duration.ofSeconds(5));
            assertThat(limiter.tryAcquire("k", 1, WINDOW).allowed()).isFalse();
        }
        clock.advance(Duration.ofSeconds(10));
        assertThat(limiter.tryAcquire("k", 1, WINDOW).allowed()).isTrue();
    }

    @Test
    void keysAreIndependent() {
        assertThat(limiter.tryAcquire("a", 1, WINDOW).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1, WINDOW).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1, WINDOW).allowed()).isTrue();
    }

    @Test
    void fiftyThreadsOnTheSameKeyNeverExceedTheLimit() throws Exception {
        int threads = 50;
        int perThread = 20;
        int limit = 37;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    int accepted = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (limiter.tryAcquire("shared", limit, WINDOW).allowed()) {
                            accepted++;
                        }
                    }
                    return accepted;
                }));
            }
            int total = 0;
            for (Future<Integer> r : results) {
                total += r.get(30, TimeUnit.SECONDS);
            }
            assertThat(total).isEqualTo(limit);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void manyRoundsOfFiftySimultaneousFirstAttemptsNeverExceedTheLimit() throws Exception {
        // cada ronda, una clave nueva y 50 hilos soltados a la vez por la barrera: la carrera entre comprobar el
        // tamaño y añadir el instante aparece en alguna ronda si la decisión no está bajo el monitor de la clave
        int threads = 50;
        int limit = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 300; round++) {
                String key = "round-" + round;
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    results.add(pool.submit(() -> {
                        start.await();
                        return limiter.tryAcquire(key, limit, WINDOW).allowed();
                    }));
                }
                int accepted = 0;
                for (Future<Boolean> r : results) {
                    accepted += r.get(30, TimeUnit.SECONDS) ? 1 : 0;
                }
                assertThat(accepted).as("round %d", round).isEqualTo(limit);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentSweepNeverLetsAKeyExceedTheLimit() throws Exception {
        // la limpieza corre a la vez que las peticiones; la ventana nunca expira, así que el total debe ser el límite
        int limit = 25;
        ExecutorService pool = Executors.newFixedThreadPool(9);
        CyclicBarrier start = new CyclicBarrier(9);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                results.add(pool.submit(() -> {
                    start.await();
                    int accepted = 0;
                    for (int i = 0; i < 2_000; i++) {
                        if (limiter.tryAcquire("k" + (i % 3), limit, WINDOW).allowed()) {
                            accepted++;
                        }
                    }
                    return accepted;
                }));
            }
            Future<?> sweeper = pool.submit(() -> {
                start.await();
                for (int i = 0; i < 2_000; i++) {
                    limiter.sweep();
                }
                return null;
            });
            int total = 0;
            for (Future<Integer> r : results) {
                total += r.get(30, TimeUnit.SECONDS);
            }
            sweeper.get(30, TimeUnit.SECONDS);
            assertThat(total).isEqualTo(3 * limit);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sweepForgetsKeysWithoutRequestsInTheirWindow_noMemoryGrowthWith100kIps() {
        for (int i = 0; i < 100_000; i++) {
            limiter.tryAcquire("login|10." + (i >> 16 & 255) + "." + (i >> 8 & 255) + "." + (i & 255), 5, WINDOW);
        }
        assertThat(limiter.trackedKeys()).isEqualTo(100_000);
        clock.advance(WINDOW.minusSeconds(1));
        limiter.sweep();
        assertThat(limiter.trackedKeys()).as("still inside the window").isEqualTo(100_000);
        clock.advance(Duration.ofSeconds(1));
        limiter.sweep();
        assertThat(limiter.trackedKeys()).isZero();
    }

    @Test
    void whenFullEvenAfterSweepingANewKeyIsRejectedButKnownKeysKeepWorking() {
        SlidingWindowRateLimiter small = new SlidingWindowRateLimiter(clock, 3);
        assertThat(small.tryAcquire("a", 10, WINDOW).allowed()).isTrue();
        assertThat(small.tryAcquire("b", 10, WINDOW).allowed()).isTrue();
        assertThat(small.tryAcquire("c", 10, WINDOW).allowed()).isTrue();

        SlidingWindowRateLimiter.Decision fourth = small.tryAcquire("d", 10, WINDOW);
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.retryAfterSeconds()).isEqualTo(60);
        assertThat(small.trackedKeys()).isEqualTo(3);
        assertThat(small.tryAcquire("a", 10, WINDOW).allowed()).isTrue();

        // cuando las ventanas expiran, la limpieza forzada libera sitio
        clock.advance(WINDOW.plusSeconds(1));
        assertThat(small.tryAcquire("d", 10, WINDOW).allowed()).isTrue();
        assertThat(small.trackedKeys()).isEqualTo(1);
    }

    @Test
    void retryAfterIsAtLeastOneSecond() {
        limiter.tryAcquire("k", 1, WINDOW);
        clock.advance(WINDOW.minusMillis(10));
        assertThat(limiter.tryAcquire("k", 1, WINDOW).retryAfterSeconds()).isEqualTo(1);
    }

    @Test
    void refusesANonPositiveCapacity() {
        assertThatThrownBy(() -> new SlidingWindowRateLimiter(clock, 0))
                .isInstanceOf(RateLimitConfigurationException.class);
    }
}
