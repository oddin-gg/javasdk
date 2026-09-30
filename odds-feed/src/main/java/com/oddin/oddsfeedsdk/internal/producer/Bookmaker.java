package com.oddin.oddsfeedsdk.internal.producer;

import com.oddin.oddsfeedsdk.api.BookmakerDetail;
import com.oddin.oddsfeedsdk.exceptions.InitException;
import com.oddin.oddsfeedsdk.schema.rest.v1.RABookmakerDetail;
import java.math.BigInteger;
import java.util.Date;
import javax.xml.datatype.XMLGregorianCalendar;
import org.jspecify.annotations.Nullable;

/**
 * Who the access token belongs to, from whoami.
 *
 * @param expireAt when the access token expires, read as 0.0.x read it: a time without a zone is in
 *     the JVM's default zone
 */
public record Bookmaker(Date expireAt, int bookmakerId, String virtualHost) implements BookmakerDetail {

    public Bookmaker {
        expireAt = new Date(expireAt.getTime());
    }

    /**
     * The details whoami sent.
     *
     * @throws InitException when an attribute the feed needs is missing
     */
    public static Bookmaker from(RABookmakerDetail whoami) {
        XMLGregorianCalendar expireAt = required(whoami.getExpireAt(), "expire_at");
        BigInteger id = required(whoami.getBookmakerId(), "bookmaker_id");
        String virtualHost = required(whoami.getVirtualHost(), "virtual_host");
        return new Bookmaker(expireAt.toGregorianCalendar().getTime(), id.intValue(), virtualHost);
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

    @Override
    public Date getExpireAt() {
        return new Date(expireAt.getTime());
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
