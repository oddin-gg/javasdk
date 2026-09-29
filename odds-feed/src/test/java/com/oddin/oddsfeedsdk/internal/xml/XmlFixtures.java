package com.oddin.oddsfeedsdk.internal.xml;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** The vendored schema and fixtures, and the write-back comparison the golden tests make with them. */
final class XmlFixtures {

    private XmlFixtures() {}

    static Path vendored() throws URISyntaxException {
        URL source = requireNonNull(
                XmlFixtures.class.getResource("/oddsfeedschema/SOURCE"),
                "the vendored schema is not on the test classpath");
        return requireNonNull(Path.of(source.toURI()).getParent());
    }

    static byte[] bytes(String xml) {
        return xml.getBytes(UTF_8);
    }

    static List<Path> xmlFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(f -> f.toString().endsWith(".xml")).sorted().toList();
        }
    }

    /**
     * Every XSD directly in {@code directory} in one schema; what they include comes along. They have
     * no target namespace, and a schema factory given several such documents keeps only the first, so
     * one document includes them all instead.
     */
    static Schema schema(Path directory) throws IOException, SAXException {
        var includes = new StringBuilder();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path xsd :
                    files.filter(f -> f.toString().endsWith(".xsd")).sorted().toList()) {
                includes.append("  <xs:include schemaLocation=\"")
                        .append(xsd.getFileName())
                        .append("\"/>\n");
            }
        }
        String all = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">
                %s</xs:schema>
                """.formatted(includes);
        var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file");
        Source source = new StreamSource(
                new StringReader(all), directory.resolve("all.xsd").toUri().toString());
        return factory.newSchema(source);
    }

    /**
     * What {@code decoded} writes back as, compared with the fixture it came from: every element and
     * attribute in the same place with the same value. A class without a root element of its own is
     * written under the fixture's root name. Adds what differs to {@code differences}.
     */
    static void compareWrittenBack(
            Path fixture, Object decoded, Marshaller marshaller, String where, List<String> differences)
            throws Exception {
        Element expected = parse(new InputSource(fixture.toUri().toString()));
        var written = new StringWriter();
        marshaller.marshal(rooted(decoded, expected.getLocalName()), written);
        compare(expected, parse(new InputSource(new StringReader(written.toString()))), where, differences);
    }

    @SuppressWarnings({"unchecked", "rawtypes"}) // JAXBElement's type parameter is the decoded class
    private static Object rooted(Object decoded, String rootName) {
        if (decoded.getClass().isAnnotationPresent(XmlRootElement.class)) {
            return decoded;
        }
        return new JAXBElement(new QName(rootName), decoded.getClass(), decoded);
    }

    private static Element parse(InputSource source) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(source).getDocumentElement();
    }

    /** Elements by name and order, attributes by name and value; numbers compare as numbers. */
    private static void compare(Element expected, Element actual, String where, List<String> differences) {
        String path = where + " <" + expected.getLocalName() + ">";
        if (!expected.getLocalName().equals(actual.getLocalName())) {
            differences.add(path + ": written back as <" + actual.getLocalName() + ">");
            return;
        }
        var want = attributes(expected);
        var got = attributes(actual);
        for (var entry : want.entrySet()) {
            String value = got.get(entry.getKey());
            if (value == null) {
                differences.add(path + " @" + entry.getKey() + ": lost");
            } else if (!sameValue(entry.getValue(), value)) {
                differences.add(path + " @" + entry.getKey() + ": " + entry.getValue() + " came back as " + value);
            }
        }
        got.keySet().stream()
                .filter(name -> !want.containsKey(name))
                .forEach(name -> differences.add(path + " @" + name + ": appeared"));
        List<Element> wantChildren = children(expected);
        List<Element> gotChildren = children(actual);
        if (wantChildren.size() != gotChildren.size()) {
            differences.add(path + ": " + wantChildren.size() + " child elements came back as " + gotChildren.size());
            return;
        }
        if (wantChildren.isEmpty() && !text(expected).equals(text(actual))) {
            differences.add(path + ": text " + text(expected) + " came back as " + text(actual));
        }
        for (int i = 0; i < wantChildren.size(); i++) {
            compare(wantChildren.get(i), gotChildren.get(i), path, differences);
        }
    }

    private static String text(Element element) {
        return element.getTextContent().strip();
    }

    private static Map<String, String> attributes(Element element) {
        var attributes = new TreeMap<String, String>();
        var all = element.getAttributes();
        for (int i = 0; i < all.getLength(); i++) {
            var attribute = all.item(i);
            if (!XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) {
                attributes.put(attribute.getLocalName(), attribute.getNodeValue());
            }
        }
        return attributes;
    }

    private static List<Element> children(Element element) {
        var children = new ArrayList<Element>();
        for (var node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element child) {
                children.add(child);
            }
        }
        return children;
    }

    /** "0.50" and "0.5" are the same number; "true" and "1" are not the same text. */
    private static boolean sameValue(String expected, String actual) {
        if (expected.equals(actual)) {
            return true;
        }
        try {
            return new BigDecimal(expected).compareTo(new BigDecimal(actual)) == 0;
        } catch (NumberFormatException notNumbers) {
            return false;
        }
    }
}
