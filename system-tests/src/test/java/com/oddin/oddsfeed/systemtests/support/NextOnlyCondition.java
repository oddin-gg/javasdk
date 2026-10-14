package com.oddin.oddsfeed.systemtests.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Disables a {@link NextOnly} class against 0.0.x; the second guard behind the POM's exclusion. */
public final class NextOnlyCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return evaluate(KnownDifference.lineUnderTest());
    }

    /** What the condition says of a run against {@code line}; apart so that a test can ask for either line. */
    static ConditionEvaluationResult evaluate(KnownDifference.Line line) {
        return line == KnownDifference.Line.NEXT
                ? ConditionEvaluationResult.enabled("1.0 under test")
                : ConditionEvaluationResult.disabled("only 1.0 has the behaviour this scenario checks");
    }
}
