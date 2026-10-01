package com.oddin.oddsfeedsdk.internal.xml;

import static org.assertj.core.api.Assertions.assertThat;

import com.ctc.wstx.stax.WstxInputFactory;
import com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory;
import org.junit.jupiter.api.Test;

/** The parser the decoders read with. */
class XmlReaderTest {

    @Test
    void readsWithWoodstoxWhateverStaxProviderTheApplicationBrings() {
        String before = System.getProperty("javax.xml.stream.XMLInputFactory");
        // an application asking for the JDK's own parser, by the standard lookup
        System.setProperty("javax.xml.stream.XMLInputFactory", "com.sun.xml.internal.stream.XMLInputFactoryImpl");
        try {
            var reader = new XmlReader(XmlReader.context(ObjectFactory.class), "message", "feed message", 1_000, null);
            assertThat(reader.inputs())
                    .as("about three times as fast under JAXB as the JDK's own")
                    .isInstanceOf(WstxInputFactory.class);
        } finally {
            if (before == null) {
                System.clearProperty("javax.xml.stream.XMLInputFactory");
            } else {
                System.setProperty("javax.xml.stream.XMLInputFactory", before);
            }
        }
    }
}
