package com.oddin.oddsfeedsdk.internal.producer;

import com.oddin.oddsfeedsdk.api.BookmakerDetail;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.xml.datatype.XMLGregorianCalendar;
import org.jspecify.annotations.Nullable;

/**
 * Who the access token belongs to, from whoami.
 *
 * @param expireAt when the access token expires, read as 0.0.x read it: a time without a zone is in
 *     the JVM's default zone
 */
public record Bookmaker(Instant expireAt, int bookmakerId, String virtualHost) implements BookmakerDetail {

    /** How soon an expiry is worth a warning, as 0.0.x warned. */
    static final Duration EXPIRES_SOON = Duration.ofDays(7);

    /**
     * The details whoami sent.
     *
     * @throws InitException when an attribute the feed needs is missing
     */
    public static Bookmaker from(RABookmakerDetail whoami) {
        XMLGregorianCalendar expireAt = required(whoami.getExpireAt(), "expire_at");
        BigInteger id = required(whoami.getBookmakerId(), "bookmaker_id");
        String virtualHost = required(whoami.getVirtualHost(), "virtual_host");
        return new Bookmaker(expireAt.toGregorianCalendar().toInstant(), id.intValue(), virtualHost);
    }

    private static <T> T required(@Nullable T value, String attribute) {
        if (value == null) {
            throw new InitException("Failed to init odds feed: whoami answered without " + attribute, null);
        }
        return value;
    }

    /**
     * The name the feed's broker connection goes by, 0.0.x's {@code of-sdk-<bookmaker>-<node>}, the
     * node -1 when none is set.
     */
    public String connectionName(@Nullable Integer nodeId) {
        return "of-sdk-" + bookmakerId + "-" + (nodeId == null ? -1 : nodeId);
    }

    /**
     * Whether the access token expires within seven days of {@code now}: 0.0.x warned then, so a
     * client has notice before the broker refuses it.
     */
    public boolean expiresSoon(Instant now) {
        return expireAt.isBefore(now.plus(EXPIRES_SOON));
    }

    @Override
    public Date getExpireAt() {
        return Date.from(expireAt);
    }

    @Override
    public int getBookmakerId() {
        return bookmakerId;
    }

    @Override
    public String getVirtualHost() {
        return virtualHost;
    }
}
