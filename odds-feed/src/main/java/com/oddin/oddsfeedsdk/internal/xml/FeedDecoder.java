package com.oddin.oddsfeedsdk.internal.xml;

import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory;
import jakarta.xml.bind.JAXBContext;
import javax.xml.validation.Schema;
import org.jspecify.annotations.Nullable;

/**
 * Turns the body of a feed message into its XML class.
 *
 * <p>This is where untrusted input enters, so it takes no chances: the body is measured before a
 * parser sees it, and the parser reads no DTD and resolves no external entity. An element or
 * attribute the classes do not know is skipped, so a producer that starts sending something new
 * does not break decoding. In strict mode, which the tests use, the same document is also validated
 * against the schema and anything JAXB could not place fails it, so that drift shows up in CI.
 *
 * <p>Outside strict mode it does not validate, as 0.0.x did not: validating every message would
 * refuse the new content the skipping exists for. A required attribute a message lacks reads as
 * absent - null for the enums and their numbers, the type's default for the other numbers, as in
 * 0.0.x - and building the message from it is where a message that lacks what it needs is refused.
 *
 * <p>Safe for concurrent use.
 */
public final class FeedDecoder {

    /** The largest body decoded by default: the feed's messages are a few kilobytes. */
    public static final int DEFAULT_MAX_BYTES = 1 << 20;

    /** The deepest nesting decoded: the feed's messages are five or six levels deep. */
    static final int MAX_DEPTH = XmlReader.MAX_DEPTH;

    private static final JAXBContext CONTEXT = XmlReader.context(ObjectFactory.class);

    private final XmlReader reader;

    private FeedDecoder(int maxBytes, @Nullable Schema schema) {
        this.reader = new XmlReader(CONTEXT, "message", "feed message", maxBytes, schema);
    }

    /** The decoder the SDK uses: bodies up to {@code maxBytes}, unknown content skipped. */
    public static FeedDecoder lenient(int maxBytes) {
        return new FeedDecoder(maxBytes, null);
    }

    /** A decoder that also validates against {@code schema} and fails on anything it cannot place. */
    public static FeedDecoder strict(int maxBytes, Schema schema) {
        return new FeedDecoder(maxBytes, schema);
    }

    public UnparsedMessage decode(byte[] body) throws DecodeException {
        Object decoded = reader.read(body);
        if (!(decoded instanceof UnparsedMessage message)) {
            throw new DecodeException(
                    "message is not a feed message: " + decoded.getClass().getSimpleName());
        }
        return message;
    }
}
