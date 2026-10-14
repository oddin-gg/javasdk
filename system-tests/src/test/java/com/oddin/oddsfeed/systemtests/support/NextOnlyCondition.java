package com.oddin.oddsfeed.systemtests.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Disables a {@link NextOnly} class against 0.0.x; the second guard behind the POM's exclusion. */
public final class NextOnlyCondition implements ExecutionCondition {

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        return KnownDifference.lineUnderTest() == KnownDifference.Line.NEXT
                ? ConditionEvaluationResult.enabled("1.0 under test")
                : ConditionEvaluationResult.disabled("only 1.0 has the behaviour this scenario checks");
    }
}
