package com.oddin.oddsfeedsdk.internal.rest;

import com.oddin.oddsfeedsdk.api.ResponseWithCode;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import com.oddin.oddsfeedsdk.exceptions.ApiException;
import com.oddin.oddsfeedsdk.internal.SdkVersion;
import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.RestDecoder;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAError;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAResponseCode;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/**
 * Makes API calls: one {@link HttpClient} per feed, three permit pools, one deadline per call and
 * the retry policy.
 *
 * <p>A call's deadline is the HTTP client timeout. Waiting for a permit, every attempt and every
 * pause between attempts come out of it, so a call takes at most that long whatever happens. A
 * permit is held for one attempt, never across the pause before the next.
 *
 * <p>Retries, the Go SDK's policy: at most three attempts, with a backoff from half a second
 * doubling up to five, each with some jitter. A 429 is retried for every call - the API did not do
 * what it was asked - after its {@code Retry-After} if that is longer than the backoff, and not at
 * all if it is longer than the time left. A 5xx or a failed connection is retried only for an
 * idempotent call, since the API may have done the work before failing. Any other status is final.
 * 401 and 403 are also reported as refused, because no retry of any call can succeed after them.
 *
 * <p>Safe for concurrent use.
 */
public final class RestTransport implements AutoCloseable {

    static final int MAX_ATTEMPTS = 3;
    static final Duration FIRST_BACKOFF = Duration.ofMillis(500);
    static final Duration MAX_BACKOFF = Duration.ofSeconds(5);
    static final int RECOVERY_PERMITS = 2;
    static final int CATALOG_PERMITS = 2;
    /** An error's body is read this far for the API's error; the rest is not needed. */
    static final int MAX_ERROR_BYTES = 1 << 20;

    private final HttpClient http;
    private final String base;
    private final String accessToken;
    private final Duration timeout;
    private final Semaphore recoveryPermits = new Semaphore(RECOVERY_PERMITS, true);
    private final Semaphore catalogPermits = new Semaphore(CATALOG_PERMITS, true);
    private final Semaphore dataPermits;
    private final RestDecoder decoder = RestDecoder.lenient(RestDecoder.DEFAULT_MAX_BYTES);
    private final ApiEvents events;
    private volatile boolean closed;

    public RestTransport(OddsFeedConfiguration configuration, ApiEvents events) {
        this(configuration, events, null);
    }

    /** With the TLS a test's fake server needs; null for the JVM's default. */
    RestTransport(OddsFeedConfiguration configuration, ApiEvents events, @Nullable SSLContext tls) {
        this.base = "https://" + configuration.getSelectedEnvironment().getApiHost() + "/v1";
        this.accessToken = configuration.getAccessToken();
        this.timeout = configuration.getHttpClientTimeout();
        this.events = events;
        this.dataPermits = new Semaphore(configuration.getRestConcurrencyLimit(), true);
        HttpClient.Builder builder =
                HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        if (tls != null) {
            builder.sslContext(tls);
        }
        this.http = builder.build();
    }

    /** A new deadline for one call: the HTTP client timeout from now. */
    public Deadline deadline() {
        return Deadline.in(timeout);
    }

    /**
     * GETs {@code path} and decodes the answer as {@code type}.
     *
     * @param path after the version, with its query, encoded: {@code /users/whoami}
     * @throws ApiException when the call fails, or its answer cannot be read or says it is not OK
     */
    public <T> T get(Pool pool, String path, Class<T> type, Deadline deadline) {
        Answer answer = call("GET", pool, path, true, deadline);
        T decoded;
        try {
            decoded = decoder.decode(answer.body(), type);
        } catch (DecodeException e) {
            throw new ApiException(failed("GET") + ": the answer to " + answer.uri() + " could not be read", null, e);
        }
        if (decoded instanceof ResponseWithCode withCode && withCode.getResponseCode() != RAResponseCode.OK) {
            throw new ApiException("Not acceptable response code from API: " + withCode.getResponseCode());
        }
        events.received(answer.uri(), decoded, answer.body());
        return decoded;
    }

    /**
     * Sends a request without a body, whose answer only has to be a success.
     *
     * @param idempotent whether repeating it does no harm, which lets a 5xx or a failed
     *     connection be retried
     * @throws ApiException when the call fails
     */
    public void send(String method, Pool pool, String path, boolean idempotent, Deadline deadline) {
        call(method, pool, path, idempotent, deadline);
    }

    @Override
    public void close() {
        closed = true;
        http.shutdownNow();
    }

    private Answer call(String method, Pool pool, String path, boolean idempotent, Deadline deadline) {
        URI uri = URI.create(base + path);
        Semaphore permit = switch (pool) {
            case RECOVERY -> recoveryPermits;
            case CATALOG -> catalogPermits;
            case DATA -> dataPermits;
        };
        var last = new Last();
        for (int attempt = 1; ; attempt++) {
            if (closed) {
                throw last.failure(method, uri, "the feed is closed");
            }
            if (!acquire(permit, deadline)) {
                throw last.failure(method, uri, late(deadline, "waiting for its turn"));
            }
            long started = System.nanoTime();
            HttpResponse<InputStream> response;
            byte[] body;
            try {
                response = http.send(request(method, uri, deadline), HttpResponse.BodyHandlers.ofInputStream());
                body = read(response, deadline);
            } catch (HttpTimeoutException e) {
                report(method, uri, 0, started, attempt, e);
                throw last.set(e, null).failure(method, uri, late(deadline, "waiting for the answer"));
            } catch (IOException e) {
                report(method, uri, 0, started, attempt, e);
                last.set(e, null);
                if (!idempotent || closed) {
                    throw last.failure(method, uri, e.toString());
                }
                pause(method, uri, attempt, backoff(attempt), deadline, last);
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw last.set(e, null).failure(method, uri, "interrupted");
            } finally {
                permit.release();
            }

            int status = response.statusCode();
            if (status / 100 == 2) {
                report(method, uri, status, started, attempt, null);
                return new Answer(uri, body);
            }
            var error = new HttpStatusException(method, uri, status);
            ApiCall call = report(method, uri, status, started, attempt, error);
            last.set(error, apiError(body));
            if (status == 401 || status == 403) {
                events.refused(call);
                throw last.failure(method, uri, "answered " + status);
            }
            boolean retry = status == 429 || (status / 100 == 5 && idempotent);
            if (!retry) {
                throw last.failure(method, uri, "answered " + status);
            }
            Duration wait = backoff(attempt);
            if (status == 429) {
                Duration asked = retryAfter(response);
                if (asked != null && asked.compareTo(wait) > 0) {
                    wait = asked;
                }
            }
            pause(method, uri, attempt, wait, deadline, last);
        }
    }

    /**
     * Before the next attempt: gives up when there are no attempts left, or no time for the pause
     * and something after it.
     */
    private static void pause(String method, URI uri, int attempt, Duration wait, Deadline deadline, Last last) {
        if (attempt >= MAX_ATTEMPTS) {
            throw last.failure(method, uri, "gave up after " + attempt + " attempts");
        }
        if (wait.compareTo(deadline.remaining()) >= 0) {
            throw last.failure(method, uri, late(deadline, "before it could try again"));
        }
        try {
            Thread.sleep(wait);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw last.set(e, null).failure(method, uri, "interrupted");
        }
    }

    private HttpRequest request(String method, URI uri, Deadline deadline) {
        return HttpRequest.newBuilder(uri)
                .timeout(atLeastAMillisecond(deadline.remaining()))
                .header("Accept", "application/xml")
                .header("X-Access-Token", accessToken)
                .header("User-Agent", SdkVersion.userAgent())
                .header("X-Oddin-SDK-Version", SdkVersion.version())
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
    }

    /**
     * The whole body of a success, or the start of an error's. The request timeout covers only the
     * wait for the headers, so a body still coming at the deadline is cut off here.
     */
    private static byte[] read(HttpResponse<InputStream> response, Deadline deadline) throws IOException {
        int limit = response.statusCode() / 100 == 2 ? RestDecoder.DEFAULT_MAX_BYTES : MAX_ERROR_BYTES;
        try (InputStream in = response.body()) {
            // RestDecoder refuses a body over its limit, so one byte more is enough to tell
            byte[] body = in.readNBytes(limit + 1);
            if (deadline.passed()) {
                throw new HttpTimeoutException("the answer was still arriving at the deadline");
            }
            return body;
        }
    }

    private boolean acquire(Semaphore permit, Deadline deadline) {
        try {
            return permit.tryAcquire(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private ApiCall report(String method, URI uri, int status, long started, int attempt, @Nullable Exception failure) {
        var call = new ApiCall(method, uri, status, Duration.ofNanos(System.nanoTime() - started), attempt, failure);
        events.called(call);
        return call;
    }

    private @Nullable RAError apiError(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            return decoder.decode(body, RAError.class);
        } catch (DecodeException notTheApisError) {
            return null;
        }
    }

    private static String late(Deadline deadline, String when) {
        return "not done within " + deadline.budget().toMillis() + " ms, " + when;
    }

    /**
     * What went wrong last in a call, for the exception it ends with: the failure as the cause, and
     * the API's own error, which then gives the exception its message as in 0.0.x.
     */
    private static final class Last {
        private @Nullable Exception cause;
        private @Nullable RAError error;

        Last set(Exception cause, @Nullable RAError error) {
            this.cause = cause;
            this.error = error;
            return this;
        }

        ApiException failure(String method, URI uri, String why) {
            return new ApiException(failed(method) + ": " + method + " " + uri + ": " + why, error, cause);
        }
    }

    /** 0.0.x's messages, which a client's logs may be matching. */
    private static String failed(String method) {
        return switch (method) {
            case "GET" -> "Failed to get data";
            case "PUT" -> "Failed to put data";
            case "DELETE" -> "Failed to delete data";
            default -> "Failed to post data";
        };
    }

    static Duration backoff(int attempt) {
        Duration base = FIRST_BACKOFF.multipliedBy(1L << Math.min(attempt - 1, 10));
        if (base.compareTo(MAX_BACKOFF) > 0) {
            base = MAX_BACKOFF;
        }
        double jitter = ThreadLocalRandom.current().nextDouble(0.7, 1.3);
        return Duration.ofNanos((long) (base.toNanos() * jitter));
    }

    /** {@code Retry-After} in seconds or as an HTTP date; null when absent or unreadable. */
    static @Nullable Duration retryAfter(HttpResponse<?> response) {
        String value = response.headers().firstValue("Retry-After").orElse(null);
        if (value == null) {
            return null;
        }
        value = value.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            try {
                Instant at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                Duration until = Duration.between(Instant.now(), at);
                return until.isNegative() ? Duration.ZERO : until;
            } catch (DateTimeParseException notADate) {
                return null;
            }
        }
    }

    private static Duration atLeastAMillisecond(Duration duration) {
        return duration.compareTo(Duration.ofMillis(1)) < 0 ? Duration.ofMillis(1) : duration;
    }

    @SuppressWarnings("ArrayRecordComponent") // private, and never compared or hashed
    private record Answer(URI uri, byte[] body) {}
}
