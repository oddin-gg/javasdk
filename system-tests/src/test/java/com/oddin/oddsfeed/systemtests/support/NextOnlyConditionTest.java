package com.oddin.oddsfeed.systemtests.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * The second guard of {@link NextOnly}, for a run the POM's exclusion does not cover: it turns a
 * tagged class off against 0.0.x and leaves it on against 1.0, and the annotation carries it. Runs
 * against both lines, and asks the condition for both whichever is under test.
 */
class NextOnlyConditionTest {

    @Test
    void aTaggedClassIsDisabledOnTheLegacyLineAndEnabledOnTheNewOne() {
        assertThat(NextOnlyCondition.evaluate(KnownDifference.Line.LEGACY).isDisabled())
                .as("disabled on 0.0.x")
                .isTrue();
        assertThat(NextOnlyCondition.evaluate(KnownDifference.Line.NEXT).isDisabled())
                .as("disabled on 1.0")
                .isFalse();
    }

    @Test
    void theConditionAsksTheLineUnderTest() {
        var asked = new NextOnlyCondition().evaluateExecutionCondition(null);
        assertThat(asked.isDisabled())
                .as("disabled when the line under test is " + KnownDifference.lineUnderTest())
                .isEqualTo(KnownDifference.lineUnderTest() == KnownDifference.Line.LEGACY);
    }

    @Test
    void theAnnotationCarriesTheCondition() {
        assertThat(AnnotationSupport.findRepeatableAnnotations(NextOnly.class, ExtendWith.class).stream()
                        .flatMap(extendWith -> Arrays.stream(extendWith.value())))
                .as("what NextOnly extends a class with")
                .contains(NextOnlyCondition.class);
    }
}
