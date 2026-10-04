package com.oddin.oddsfeedsdk.internal.descriptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.exceptions.ApiException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** A read in several locales, on its own: what an interrupted caller does. */
class InLocalesTest {

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void anInterruptedCallerStopsWaitingAtOnceKeepsTheInterruptAndFails() throws Exception {
        var loading = new CountDownLatch(2);
        var never = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var stillInterrupted = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().start(() -> {
            try {
                InLocales.first(
                        List.of(Locale.ENGLISH, Locale.GERMAN),
                        _ -> false,
                        _ -> {
                            loading.countDown();
                            try {
                                never.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "loaded";
                        },
                        threads);
            } catch (RuntimeException e) {
                failure.set(e);
                stillInterrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        assertThat(loading.await(5, TimeUnit.SECONDS))
                .as("both locales loading")
                .isTrue();
        caller.interrupt();
        caller.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(caller.isAlive()).as("released at once").isFalse();
        assertThat(failure.get()).isInstanceOf(ApiException.class).hasMessageContaining("interrupted");
        assertThat(stillInterrupted).isTrue();
    }
}
