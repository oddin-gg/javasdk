package com.oddin.oddsfeedsdk.utils

import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.bind.UnmarshalException
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Where the XML of the feed and of the REST API is read from.
 *
 * A default JAXB unmarshaller resolves a DTD and the external entities it declares, so a
 * message or a response carrying `<!ENTITY x SYSTEM "file:///...">` would read a local file
 * or call an address of the sender's choosing. The reader made here has DTDs and external
 * entities off: an entity reference is refused, and a DOCTYPE is skipped without fetching
 * what it points to. Valid messages carry neither.
 *
 * The factory is configured once; creating readers from it is thread-safe, and each
 * reader belongs to the one unmarshal call it is made for.
 *
 * Making a reader reads the prolog, so a bad XML declaration or an unknown encoding fails here,
 * with a StAX exception; unmarshalling from a stream fails with a JAXB one, which is what the
 * callers handle. The reader fails the same way, so malformed input is refused as before.
 */
internal object SafeXml {
    private val factory: XMLInputFactory = XMLInputFactory.newFactory().apply {
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        // defence in depth, for the parsers that look at these instead
        try {
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        } catch (e: IllegalArgumentException) {
            // a parser without the properties has no way to follow them either
        }
    }

    fun reader(input: InputStream): XMLStreamReader =
        try {
            factory.createXMLStreamReader(input)
        } catch (e: XMLStreamException) {
            throw UnmarshalException(e.message, e)
        }
}
