package com.oddin.oddsfeedsdk.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.OddsFeed;
import org.junit.jupiter.api.Test;

/** The version the SDK reports, as the Go SDK reports its own. */
class SdkVersionTest {

    @Test
    void theJarReportsTheVersionItWasBuiltAs() {
        String built = System.getProperty("sdk.built.version");
        assertThat(built).as("the build passes its version").isNotBlank();
        assertThat(SdkVersion.version()).isEqualTo(SdkVersion.reported(built)).doesNotContain("${");
        assertThat(OddsFeed.getSdkVersion()).isEqualTo(SdkVersion.version());
    }

    @Test
    void aReleaseReportsItsVersionAndAnyOtherBuildIsMarkedDev() {
        assertThat(SdkVersion.reported("1.0.0")).isEqualTo("1.0.0");
        assertThat(SdkVersion.reported("1.2.0-SNAPSHOT")).isEqualTo("1.2.0-dev");
        assertThat(SdkVersion.reported("${project.version}")).isEqualTo(SdkVersion.UNKNOWN);
        assertThat(SdkVersion.reported(null)).isEqualTo(SdkVersion.UNKNOWN);
    }

    @Test
    void theUserAgentNamesTheSdkAndTheJavaItRunsOn() {
        assertThat(SdkVersion.userAgent())
                .isEqualTo("oddin-javasdk/" + SdkVersion.version() + " (java " + Runtime.version() + ")");
    }
}
