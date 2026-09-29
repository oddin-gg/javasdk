package com.oddin.oddsfeedsdk.internal.xml;

import com.oddin.oddsfeedsdk.schema.rest.v1.ObjectFactory;
import jakarta.xml.bind.JAXBContext;
import javax.xml.validation.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Turns the body of an API response into its XML class, with the same guards as the feed's decoder:
 * measured first, no DTD, no external entity, unknown content skipped, and in strict mode validated
 * against the schema.
 *
 * <p>A response whose root element is of a named schema type, such as {@code match_summary}, decodes
 * to that type's class, not to a JAXB element wrapping it.
 *
 * <p>Safe for concurrent use.
 */
public final class RestDecoder {

    /**
     * The largest body decoded by default. Responses are bigger than feed messages - the market
     * descriptions of one language are the largest - so the limit is here to stop a runaway body,
     * not to fit a typical one.
     */
    public static final int DEFAULT_MAX_BYTES = 32 << 20;

    private static final JAXBContext CONTEXT = XmlReader.context(ObjectFactory.class);

    private final XmlReader reader;

    private RestDecoder(int maxBytes, @Nullable Schema schema) {
        this.reader = new XmlReader(CONTEXT, "response", "API response", maxBytes, schema);
    }

    /** The decoder the SDK uses: bodies up to {@code maxBytes}, unknown content skipped. */
    public static RestDecoder lenient(int maxBytes) {
        return new RestDecoder(maxBytes, null);
    }

    /** A decoder that also validates against {@code schema} and fails on anything it cannot place. */
    public static RestDecoder strict(int maxBytes, Schema schema) {
        return new RestDecoder(maxBytes, schema);
    }

    /** The response as {@code type}; a response of another type fails, as a malformed one does. */
    public <T> T decode(byte[] body, Class<T> type) throws DecodeException {
        Object decoded = reader.read(body);
        if (!type.isInstance(decoded)) {
            throw new DecodeException("response is not " + type.getSimpleName() + " but "
                    + decoded.getClass().getSimpleName());
        }
        return type.cast(decoded);
    }

    /** The response as whatever its root element says it is. */
    public Object decode(byte[] body) throws DecodeException {
        return reader.read(body);
    }
}
