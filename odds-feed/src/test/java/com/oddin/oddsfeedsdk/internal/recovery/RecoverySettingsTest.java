package com.oddin.oddsfeedsdk.internal.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.config.Environment;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** The recovery actor's numbers, from the feed's configuration and the design. */
class RecoverySettingsTest {

    @Test
    void theConfigurationsNumbersMapWithTheirUnits() {
        RecoverySettings settings = RecoverySettings.from(configuration(45, 90, 7, Duration.ofMinutes(30)));
        assertThat(settings.maxInactivity()).isEqualTo(Duration.ofSeconds(45));
        assertThat(settings.maxRecoveryTime()).isEqualTo(Duration.ofMinutes(90));
        assertThat(settings.nodeId()).isEqualTo(7);
        assertThat(settings.initialSnapshotInterval()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void whatTheConfigurationLeavesUnsetStaysUnset() {
        RecoverySettings settings = RecoverySettings.from(configuration(20, 360, null, null));
        assertThat(settings.maxInactivity()).isEqualTo(Duration.ofSeconds(20));
        assertThat(settings.maxRecoveryTime()).isEqualTo(Duration.ofHours(6));
        assertThat(settings.nodeId()).isNull();
        assertThat(settings.initialSnapshotInterval())
                .as("a full snapshot at a cold start")
                .isNull();
    }

    @Test
    void theRestAreTheDesignsNumbers() {
        RecoverySettings settings = RecoverySettings.from(configuration(20, 360, null, null));
        assertThat(settings.reissues()).isEqualTo(3);
        assertThat(settings.firstReissueBackoff()).isEqualTo(Duration.ofSeconds(5));
        assertThat(settings.cooldown()).isEqualTo(Duration.ofMinutes(10));
        assertThat(settings.aliveInterval()).isEqualTo(Duration.ofSeconds(10));
        assertThat(settings.staleLimit()).isEqualTo(Duration.ofMinutes(2));
        assertThat(settings.staleWindow()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.resets()).isEqualTo(3);
        assertThat(settings.firstResetBackoff()).isEqualTo(Duration.ofMinutes(1));
        assertThat(settings.eventRecoveries()).isEqualTo(128);
        assertThat(settings.tick()).isEqualTo(Duration.ofSeconds(1));
    }

    /** Through 0.0.x's constructor, the one way to set the maximum inactivity and recovery time. */
    private static OddsFeedConfiguration configuration(
            int maxInactivitySeconds,
            int maxRecoveryExecutionMinutes,
            @Nullable Integer nodeId,
            @Nullable Duration initialSnapshotInterval) {
        return new OddsFeedConfiguration(
                "token",
                Locale.ENGLISH,
                maxInactivitySeconds,
                maxRecoveryExecutionMinutes,
                nodeId,
                ExceptionHandlingStrategy.THROW,
                new Environment("mq.invalid", "api.invalid", 5671),
                initialSnapshotInterval,
                1,
                1,
                1,
                1);
    }
}
