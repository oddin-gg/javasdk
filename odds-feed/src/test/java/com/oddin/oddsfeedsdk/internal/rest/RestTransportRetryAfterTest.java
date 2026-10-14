package com.oddin.oddsfeedsdk.internal.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** What the transport makes of a {@code Retry-After} header: seconds, an HTTP date, or nothing it can use. */
class RestTransportRetryAfterTest {

    @Test
    void secondsAreTakenAsTheyAre() {
        assertThat(RestTransport.retryAfter(header("Retry-After", "5"))).isEqualTo(Duration.ofSeconds(5));
        assertThat(RestTransport.retryAfter(header("Retry-After", "0"))).isEqualTo(Duration.ZERO);
        assertThat(RestTransport.retryAfter(header("Retry-After", " 7 "))).isEqualTo(Duration.ofSeconds(7));
    }

    @Test
    void theHeaderNameIsNotCaseSensitive() {
        assertThat(RestTransport.retryAfter(header("retry-after", "5"))).isEqualTo(Duration.ofSeconds(5));
        assertThat(RestTransport.retryAfter(header("RETRY-AFTER", "5"))).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void withoutTheHeaderThereIsNothing() {
        assertThat(RestTransport.retryAfter(HttpHeaders.of(Map.of(), (name, value) -> true)))
                .isNull();
        assertThat(RestTransport.retryAfter(header("Retry-Later", "5"))).isNull();
    }

    @Test
    void aDateAheadIsTheTimeUntilIt() {
        // an HTTP date has whole seconds, so what is left is a little under the three asked for
        Duration asked = RestTransport.retryAfter(header("Retry-After", httpDate(3)));
        assertThat(asked).isNotNull().isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    @Test
    void aDateMayNameItsZoneAsAnOffset() {
        Duration asked =
                RestTransport.retryAfter(header("Retry-After", httpDate(3).replace("GMT", "+0000")));
        assertThat(asked).isNotNull().isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3));
        asked = RestTransport.retryAfter(header("Retry-After", "  " + httpDate(3) + "  "));
        assertThat(asked).as("trimmed").isNotNull().isBetween(Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    @Test
    void aDateInThePastIsNoWaitAtAll() {
        assertThat(RestTransport.retryAfter(header("Retry-After", "Sun, 06 Nov 1994 08:49:37 GMT")))
                .isEqualTo(Duration.ZERO);
        assertThat(RestTransport.retryAfter(header("Retry-After", httpDate(-60))))
                .isEqualTo(Duration.ZERO);
    }

    /** What is not seconds or an RFC 1123 date is not an answer: the backoff decides alone. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "soon",
                "-1",
                "1.5",
                "99999999999999999999",
                // the two other formats HTTP/1.0 allowed, which the transport does not read
                "Sunday, 06-Nov-94 08:49:37 GMT",
                "Sun Nov  6 08:49:37 1994",
                // a date whose weekday is not the day's
                "Mon, 06 Nov 1994 08:49:37 GMT"
            })
    void whatIsNeitherSecondsNorADateIsNothing(String value) {
        assertThat(RestTransport.retryAfter(header("Retry-After", value))).isNull();
    }

    /** An RFC 1123 date, {@code seconds} from now. */
    static String httpDate(long seconds) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.now(ZoneOffset.UTC).plusSeconds(seconds));
    }

    private static HttpHeaders header(String name, String value) {
        return HttpHeaders.of(Map.of(name, List.of(value)), (n, v) -> true);
    }
}
