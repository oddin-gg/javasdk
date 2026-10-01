package com.oddin.oddsfeedsdk.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The state machine depends on nothing that calls out or waits: no REST client, no broker, no
 * thread, no lock. Read from the compiled classes, every name it refers to, so a rule that made a
 * REST call on the actor's thread, or waited there, fails the build; the requests and resets go out
 * through the outbox only.
 */
class RecoveryMachineDependencyTest {

    private static final List<String> FORBIDDEN = List.of(
            "com/oddin/oddsfeedsdk/internal/rest/",
            "com/oddin/oddsfeedsdk/internal/amqp/",
            "java/net/http/",
            "com/rabbitmq/",
            "java/lang/Thread",
            "java/util/concurrent/locks/",
            "java/util/concurrent/BlockingQueue",
            "java/util/concurrent/CountDownLatch",
            "java/util/concurrent/Semaphore");

    @Test
    void theStateMachineCallsNothingThatBlocks() throws IOException, URISyntaxException {
        Path classes = Path.of(RecoveryMachine.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .resolve("com/oddin/oddsfeedsdk/internal/recovery");
        var checked = new ArrayList<String>();
        var violations = new ArrayList<String>();
        try (Stream<Path> files = Files.list(classes)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("RecoveryMachine"))
                    .toList()) {
                ClassModel model = ClassFile.of().parse(Files.readAllBytes(file));
                String name = model.thisClass().asInternalName();
                checked.add(name);
                for (PoolEntry entry : model.constantPool()) {
                    if (entry instanceof Utf8Entry text) {
                        for (String forbidden : FORBIDDEN) {
                            if (text.stringValue().contains(forbidden)) {
                                violations.add(name + " refers to " + text.stringValue());
                            }
                        }
                    }
                }
            }
        }
        assertThat(checked)
                .as("state machine classes read")
                .contains(
                        "com/oddin/oddsfeedsdk/internal/recovery/RecoveryMachine",
                        "com/oddin/oddsfeedsdk/internal/recovery/RecoveryMachine$Track");
        assertThat(violations).isEmpty();
    }
}
