package com.oddin.oddsfeedsdk.internal.xml;

import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
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
 * <p>Safe for concurrent use; each call gets its own unmarshaller.
 */
public final class FeedDecoder {

    /** The largest body decoded by default: the feed's messages are a few kilobytes. */
    public static final int DEFAULT_MAX_BYTES = 1 << 20;

    private static final JAXBContext CONTEXT = context();

    private final int maxBytes;
    private final @Nullable Schema schema;
    private final XMLInputFactory inputs;

    private FeedDecoder(int maxBytes, @Nullable Schema schema) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.schema = schema;
        this.inputs = XMLInputFactory.newFactory();
        inputs.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputs.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputs.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
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
        if (body.length > maxBytes) {
            throw new DecodeException("message of " + body.length + " bytes is over the limit of " + maxBytes);
        }
        Object decoded;
        var problems = new ArrayList<String>();
        try {
            XMLStreamReader reader = inputs.createXMLStreamReader(new ByteArrayInputStream(body));
            try {
                decoded = unmarshaller(problems).unmarshal(reader);
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | JAXBException e) {
            throw new DecodeException("message is not a well-formed feed message: " + describe(e), e);
        }
        if (!problems.isEmpty()) {
            throw new DecodeException("message does not match the schema: " + String.join("; ", problems));
        }
        if (!(decoded instanceof UnparsedMessage message)) {
            throw new DecodeException("message is not a feed message: " + decoded.getClass().getSimpleName());
        }
        return message;
    }

    private Unmarshaller unmarshaller(List<String> problems) throws JAXBException {
        Unmarshaller unmarshaller = CONTEXT.createUnmarshaller();
        if (schema != null) {
            unmarshaller.setSchema(schema);
            unmarshaller.setEventHandler(event -> {
                var locator = event.getLocator();
                problems.add(event.getMessage() + (locator == null ? "" : " (line " + locator.getLineNumber() + ")"));
                return true;
            });
        }
        return unmarshaller;
    }

    private static String describe(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getMessage() == null) {
            cause = cause.getCause();
        }
        return String.valueOf(cause.getMessage() != null ? cause.getMessage() : cause);
    }

    private static JAXBContext context() {
        try {
            return JAXBContext.newInstance(com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
