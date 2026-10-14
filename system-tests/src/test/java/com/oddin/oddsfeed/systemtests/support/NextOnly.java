package com.oddin.oddsfeed.systemtests.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Marks a scenario class that checks behaviour only 1.0 has, such as the health a 0.0.x feed cannot
 * report. The scenarios run against 1.0 and are not part of the 0.0.x run; the other scenarios
 * run against both. Use a {@link KnownDifference} instead where 0.0.x does something else that the
 * scenario can observe; see KNOWN-DIFFERENCES.md, "Scenarios only 1.0 has".
 *
 * <p>Three things keep such a class out of the 0.0.x run, so that a scenario using API 0.0.x lacks
 * neither breaks its build nor fails it: the legacy-sdk profile of the module's POM does not compile
 * the package {@value #PACKAGE} and leaves out the tag {@value #TAG}, and {@link NextOnlyCondition}
 * disables a tagged class on 0.0.x whatever started it, an IDE for one. {@code NextOnlyGuardTest}
 * holds the classes, the package and the POM to each other. Against 1.0 the tag is no filter, so
 * {@code -Dgroups=next-only} runs these scenarios alone.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag(NextOnly.TAG)
@ExtendWith(NextOnlyCondition.class)
public @interface NextOnly {

    /** The JUnit tag of the group; the POM's {@code systemtests.excludedGroups} names it. */
    String TAG = "next-only";

    /** Where the group's classes live, below the scenarios' package. */
    String PACKAGE = "com.oddin.oddsfeed.systemtests.next";
}
