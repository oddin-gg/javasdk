package com.oddin.oddsfeed.benchmarks;

import static java.util.Objects.requireNonNull;

import com.oddin.oddsfeedsdk.internal.xml.DecodeException;
import com.oddin.oddsfeedsdk.internal.xml.FeedDecoder;
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage;
import com.oddin.oddsfeedsdk.schema.feed.v1.OFOddsChange;
import com.oddin.oddsfeedsdk.schema.feed.v1.ObjectFactory;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Unmarshaller;
import java.io.ByteArrayInputStream;
import java.util.concurrent.TimeUnit;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Decoding one odds change, warm: the SDK's decoder (JAXB with an unmarshaller per message), JAXB
 * with one unmarshaller kept, and the hand-written StAX reader JAXB is weighed against. The time and
 * the allocation per message are what the budget holds.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class DecodeBenchmark {

    @Param({"20", "150", "500"})
    public int markets;

    private byte[] body = new byte[0];
    private final FeedDecoder decoder = FeedDecoder.lenient(FeedDecoder.DEFAULT_MAX_BYTES);
    private final StaxOddsChangeReader stax = new StaxOddsChangeReader();
    private final XMLInputFactory inputs = XMLInputFactory.newDefaultFactory();
    private @Nullable Unmarshaller unmarshaller;

    @Setup
    public void generate() throws JAXBException {
        body = Corpus.oddsChange(markets);
        inputs.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputs.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        unmarshaller = JAXBContext.newInstance(ObjectFactory.class).createUnmarshaller();
    }

    @Benchmark
    public UnparsedMessage jaxb() throws DecodeException {
        return decoder.decode(body);
    }

    /** What the decoder would cost with an unmarshaller per thread instead of per message. */
    @Benchmark
    public Object jaxbKeptUnmarshaller() throws JAXBException, XMLStreamException {
        XMLStreamReader reader = inputs.createXMLStreamReader(new ByteArrayInputStream(body));
        try {
            return requireNonNull(unmarshaller).unmarshal(reader);
        } finally {
            reader.close();
        }
    }

    @Benchmark
    public OFOddsChange stax() throws XMLStreamException {
        return stax.read(body);
    }
}
