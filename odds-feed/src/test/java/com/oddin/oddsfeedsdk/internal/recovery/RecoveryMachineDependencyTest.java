package com.oddin.oddsfeedsdk.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.MemberRefEntry;
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

    /**
     * What reads a clock directly: the machine reads the feed clock's two sides only, by name. A
     * call of {@code now} on any {@code java.time} class, {@code Instant.now} too, is checked
     * apart, as the caches' guard does: the name of the class alone is in the classes that only
     * build an instant.
     */
    private static final List<String> DIRECT_CLOCK_READS =
            List.of("java/time/InstantSource", "java/time/Clock", "nanoTime", "currentTimeMillis");

    @Test
    void theStateMachineReadsNoClockButTheFeedClocksTwoSides() throws IOException, URISyntaxException {
        var violations = new ArrayList<String>();
        for (ClassModel model : machineClasses()) {
            for (PoolEntry entry : model.constantPool()) {
                if (entry instanceof Utf8Entry text) {
                    for (String read : DIRECT_CLOCK_READS) {
                        if (text.stringValue().contains(read)) {
                            violations.add(model.thisClass().asInternalName() + " refers to " + text.stringValue());
                        }
                    }
                } else if (entry instanceof MemberRefEntry member
                        && member.owner().asInternalName().startsWith("java/time/")
                        && member.name().stringValue().equals("now")) {
                    violations.add(model.thisClass().asInternalName() + " calls "
                            + member.owner().asInternalName() + ".now");
                }
            }
        }
        assertThat(violations).isEmpty();
    }

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

    private static List<ClassModel> machineClasses() throws IOException, URISyntaxException {
        Path classes = Path.of(RecoveryMachine.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .resolve("com/oddin/oddsfeedsdk/internal/recovery");
        var models = new ArrayList<ClassModel>();
        try (Stream<Path> files = Files.list(classes)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith("RecoveryMachine"))
                    .toList()) {
                models.add(ClassFile.of().parse(Files.readAllBytes(file)));
            }
        }
        assertThat(models).as("state machine classes read").isNotEmpty();
        return models;
    }
}
