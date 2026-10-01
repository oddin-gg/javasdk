package com.oddin.oddsfeedsdk.internal.entity;

import com.oddin.oddsfeedsdk.exceptions.UnsupportedUrnFormatException;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.time.Instant;
import javax.xml.datatype.DatatypeConstants;
import javax.xml.datatype.XMLGregorianCalendar;
import org.jspecify.annotations.Nullable;

/** The API's values as the entity caches keep them. */
final class ApiValues {

    private ApiValues() {}

    /** An entity's id, or null when it is not a URN: left out, rather than failing the response. */
    static @Nullable URN urn(@Nullable String id) {
        if (id == null) {
            return null;
        }
        try {
            return URN.parse(id);
        } catch (UnsupportedUrnFormatException notOne) {
            return null;
        }
    }

    /** A time or a date the API sends without a zone is UTC, as 0.0.x read it. */
    static @Nullable Instant instant(@Nullable XMLGregorianCalendar time) {
        if (time == null) {
            return null;
        }
        if (time.getTimezone() == DatatypeConstants.FIELD_UNDEFINED) {
            var utc = (XMLGregorianCalendar) time.clone();
            utc.setTimezone(0);
            return utc.toGregorianCalendar().toInstant();
        }
        return time.toGregorianCalendar().toInstant();
    }
}
