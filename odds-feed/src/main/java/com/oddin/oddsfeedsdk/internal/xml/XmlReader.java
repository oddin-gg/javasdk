package com.oddin.oddsfeedsdk.internal.xml;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.util.SymbolTable;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.util.StreamReaderDelegate;
import javax.xml.validation.Schema;
import org.codehaus.stax2.XMLInputFactory2;
import org.jspecify.annotations.Nullable;

/**
 * Reads one untrusted XML document into its generated class; the feed and the REST decoders share it.
 *
 * <p>The body is measured before a parser sees it, and the parser - Woodstox, about three times as fast
 * as the JDK's own under JAXB - reads no DTD, resolves no external entity, and nests no deeper than
 * {@link #MAX_DEPTH}. An element or attribute the classes do not know is skipped. With a schema, the
 * document is also validated and anything JAXB could not place fails it, which is how the golden
 * tests catch drift.
 *
 * <p>Safe for concurrent use; each call gets its own unmarshaller.
 */
final class XmlReader {

    /** The deepest nesting read: the feed's messages and the API's responses are a few levels deep. */
    static final int MAX_DEPTH = 64;

    /**
     * The most distinct names in one document - every name the parser keeps: element and attribute
     * names, their prefixes and namespaces, and the namespaces declared; the schemas have about a
     * hundred. Woodstox keeps names in a table that searches colliding ones one by one, so a document
     * of many names built to collide would cost it time in the square of their number.
     */
    static final int MAX_NAMES = 512;

    /**
     * The most attributes and namespace declarations on one element, together; the schemas' widest
     * has 35. Woodstox holds the two in separate arrays and checks their sum only as one grows, so
     * it bounds a start tag's memory but not this sum: the name limit counts it.
     */
    static final int MAX_ATTRIBUTES = 64;

    /**
     * The longest attribute value, a namespace declared included: the API's values are names and
     * ids, and Woodstox keeps recent namespaces in a cache of its own for as long as the JVM runs.
     */
    static final int MAX_ATTRIBUTE_LENGTH = 16_384;

    /** The longest name: the schemas' are a few dozen characters; a name is compared whole, each time. */
    static final int MAX_NAME_LENGTH = 128;

    /**
     * The most distinct names one {@link String#hashCode} may have: past a few, they are built to
     * collide. Woodstox seeds its hash for each factory and mixes it, so names cannot be aimed at
     * one of its chains unless their hashes are equal whatever the seed - and names of one length
     * and first character with equal {@code String} hashes are: the seed changes only the first
     * character's share. Woodstox then mixes the hash with murmur's finalizer, which is not
     * linear, so names whose hashes merely share their low bits spread over the chains too. And
     * whatever the names, {@link #MAX_NAMES} bounds a chain: 512 names with one hash make a 1 MiB
     * body take the parser about 40 ms, against 3 ms, where thousands took it seconds.
     */
    static final int MAX_NAMES_PER_HASH = 8;

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
        // Woodstox by name, not whichever StAX provider the application brings: the settings below
        // are Woodstox's, and another provider could reject or ignore them
        this.inputs = new WstxInputFactory() {
            @Override
            public synchronized void updateSymbolTable(SymbolTable table) {
                // each document's names stay its own: none carries over to lengthen the next one's
            }
        };
        inputs.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputs.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputs.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        // a million bytes of opening tags must not become a million levels to track
        inputs.setProperty(WstxInputProperties.P_MAX_ELEMENT_DEPTH, MAX_DEPTH);
        // Woodstox reads all of a start tag before the name limit sees it: this bounds what one tag holds
        inputs.setProperty(WstxInputProperties.P_MAX_ATTRIBUTES_PER_ELEMENT, MAX_ATTRIBUTES);
        inputs.setProperty(WstxInputProperties.P_MAX_ATTRIBUTE_SIZE, MAX_ATTRIBUTE_LENGTH);
        // text read in full as the parser reaches it: lazily, an error in it would surface later, from
        // inside JAXB, as an unchecked exception instead of the parser's
        inputs.setProperty(XMLInputFactory2.P_LAZY_PARSING, false);
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
            XMLStreamReader reader = new NameLimit(inputs.createXMLStreamReader(new ByteArrayInputStream(body)));
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
        if (decoded instanceof JAXBElement<?> element) {
            // xsi:nil on the root leaves the element without a value
            if (element.isNil() || element.getValue() == null) {
                throw new DecodeException(
                        noun + " has a nil root element " + element.getName().getLocalPart());
            }
            return element.getValue();
        }
        return decoded;
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

    /** The parser it reads with; for a test. */
    XMLInputFactory inputs() {
        return inputs;
    }

    /**
     * Refuses a document once it has used more than {@link #MAX_NAMES} distinct names - every name
     * the parser keeps: element and attribute names, their prefixes and namespaces, and the
     * namespaces declared - or an element with more than {@link #MAX_ATTRIBUTES} attributes and
     * namespace declarations, and refuses any processing instruction, which neither the feed nor the
     * API sends.
     */
    static final class NameLimit extends StreamReaderDelegate {
        private final Set<String> names = new HashSet<>();
        private final Map<Integer, Integer> namesPerHash = new HashMap<>();

        NameLimit(XMLStreamReader reader) {
            super(reader);
        }

        @Override
        public int next() throws XMLStreamException {
            int event = super.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if (getAttributeCount() + getNamespaceCount() > MAX_ATTRIBUTES) {
                    throw new XMLStreamException(
                            "more than " + MAX_ATTRIBUTES + " attributes and namespace declarations on one element",
                            getLocation());
                }
                name(getLocalName());
                name(getPrefix());
                namespace(getNamespaceURI());
                for (int i = 0, n = getNamespaceCount(); i < n; i++) {
                    name(getNamespacePrefix(i));
                    namespace(getNamespaceURI(i));
                }
                for (int i = 0, n = getAttributeCount(); i < n; i++) {
                    name(getAttributeLocalName(i));
                    name(getAttributePrefix(i));
                    namespace(getAttributeNamespace(i));
                }
            } else if (event == XMLStreamConstants.PROCESSING_INSTRUCTION) {
                throw new XMLStreamException("a processing instruction", getLocation());
            }
            return event;
        }

        /** As the interface's own, but moving through {@link #next}, so nothing gets past the limit. */
        @Override
        public int nextTag() throws XMLStreamException {
            int event = next();
            while (((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) && isWhiteSpace())
                    || event == XMLStreamConstants.SPACE
                    || event == XMLStreamConstants.COMMENT) {
                event = next();
            }
            if (event != XMLStreamConstants.START_ELEMENT && event != XMLStreamConstants.END_ELEMENT) {
                throw new XMLStreamException("expected a start or an end tag", getLocation());
            }
            return event;
        }

        /** As the interface's own, but moving through {@link #next}, so nothing gets past the limit. */
        @Override
        public String getElementText() throws XMLStreamException {
            if (getEventType() != XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("not at a start tag", getLocation());
            }
            var text = new StringBuilder();
            for (int event = next(); event != XMLStreamConstants.END_ELEMENT; event = next()) {
                switch (event) {
                    case XMLStreamConstants.CHARACTERS,
                            XMLStreamConstants.CDATA,
                            XMLStreamConstants.SPACE,
                            XMLStreamConstants.ENTITY_REFERENCE -> text.append(getText());
                    case XMLStreamConstants.COMMENT -> {
                        // skipped, as the interface says
                    }
                    default -> throw new XMLStreamException("an element's text holds more than text", getLocation());
                }
            }
            return text.toString();
        }

        /** An element's, an attribute's or a prefix's name. */
        private void name(@Nullable String name) throws XMLStreamException {
            if (name != null && name.length() > MAX_NAME_LENGTH) {
                throw new XMLStreamException("a name longer than " + MAX_NAME_LENGTH, getLocation());
            }
            seen(name);
        }

        /** A namespace: a value, as long as an attribute value may be. */
        private void namespace(@Nullable String uri) throws XMLStreamException {
            seen(uri);
        }

        private void seen(@Nullable String name) throws XMLStreamException {
            if (name == null || name.isEmpty() || !names.add(name)) {
                return;
            }
            if (names.size() > MAX_NAMES) {
                throw new XMLStreamException("more than " + MAX_NAMES + " distinct names", getLocation());
            }
            if (namesPerHash.merge(name.hashCode(), 1, Integer::sum) > MAX_NAMES_PER_HASH) {
                throw new XMLStreamException(
                        "more than " + MAX_NAMES_PER_HASH + " distinct names with one hash", getLocation());
            }
        }
    }

    static JAXBContext context(Class<?> objectFactory) {
        try {
            return JAXBContext.newInstance(objectFactory);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
