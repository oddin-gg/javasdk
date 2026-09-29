package com.oddin.oddsfeedsdk.internal.xml;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
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
 * Reads one untrusted XML document into its generated class; the feed and the REST decoders share it.
 *
 * <p>The body is measured before a parser sees it, and the parser reads no DTD and resolves no
 * external entity. An element or attribute the classes do not know is skipped. With a schema, the
 * document is also validated and anything JAXB could not place fails it, which is how the golden
 * tests catch drift.
 *
 * <p>Safe for concurrent use; each call gets its own unmarshaller.
 */
final class XmlReader {

    /** The deepest nesting read: the feed's messages and the API's responses are a few levels deep. */
    static final int MAX_DEPTH = 64;

    private final JAXBContext context;
    private final String noun;
    private final String what;
    private final int maxBytes;
    private final @Nullable Schema schema;
    private final XMLInputFactory inputs;

    /** {@code noun} and {@code what} name the documents in errors: "message" and "feed message". */
    XmlReader(JAXBContext context, String noun, String what, int maxBytes, @Nullable Schema schema) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.context = context;
        this.noun = noun;
        this.what = what;
        this.maxBytes = maxBytes;
        this.schema = schema;
        // the JDK's own parser, not whichever StAX provider the application brings: the settings
        // below are the JDK's, and another provider could reject or ignore them
        this.inputs = XMLInputFactory.newDefaultFactory();
        inputs.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputs.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputs.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        // a million bytes of opening tags must not become a million levels to track
        inputs.setProperty("http://www.oracle.com/xml/jaxp/properties/maxElementDepth", MAX_DEPTH);
    }

    /**
     * The document's root as its generated class. A root element of a named type decodes to the
     * type, not to the element wrapping it.
     */
    Object read(byte[] body) throws DecodeException {
        if (body.length > maxBytes) {
            throw new DecodeException(noun + " of " + body.length + " bytes is over the limit of " + maxBytes);
        }
        Object decoded;
        var problems = new ArrayList<String>();
        try {
            XMLStreamReader reader = inputs.createXMLStreamReader(new ByteArrayInputStream(body));
            try {
                decoded = unmarshaller(problems).unmarshal(reader);
                // JAXB stops at the root's end; whatever follows must be well-formed too
                while (reader.hasNext()) {
                    reader.next();
                }
            } finally {
                reader.close();
            }
        } catch (XMLStreamException | JAXBException e) {
            throw new DecodeException(noun + " is not a well-formed " + what + ": " + describe(e), e);
        }
        if (!problems.isEmpty()) {
            throw new DecodeException(noun + " does not match the schema: " + String.join("; ", problems));
        }
        return decoded instanceof JAXBElement<?> element ? element.getValue() : decoded;
    }

    private Unmarshaller unmarshaller(List<String> problems) throws JAXBException {
        Unmarshaller unmarshaller = context.createUnmarshaller();
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

    static JAXBContext context(Class<?> objectFactory) {
        try {
            return JAXBContext.newInstance(objectFactory);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
