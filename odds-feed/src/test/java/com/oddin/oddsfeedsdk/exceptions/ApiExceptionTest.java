package com.oddin.oddsfeedsdk.exceptions;

import static org.assertj.core.api.Assertions.assertThat;

import com.oddin.oddsfeedsdk.schema.rest.v1.RAError;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/** The message an API failure carries, as in 0.0.x. */
class ApiExceptionTest {

    @Test
    void theApiErrorTakesThePlaceOfTheMessage() {
        var error = new RAError();
        error.setMessage("ERROR. Invalid market ID.");
        error.setAction("Not Found");
        var cause = new IOException("404");

        var failure = new ApiException("ignored", error, cause);
        assertThat(failure).hasMessage("ERROR. Invalid market ID. - Not Found").hasCause(cause);
        assertThat(new ApiException("ignored", error))
                .hasMessage("ERROR. Invalid market ID. - Not Found")
                .hasNoCause();
    }

    @Test
    void withoutAnApiErrorTheMessageIsKept() {
        var cause = new IOException("timeout");
        assertThat(new ApiException("request failed", null, cause))
                .hasMessage("request failed")
                .hasCause(cause);
        assertThat(new ApiException("request failed"))
                .hasMessage("request failed")
                .hasNoCause();
        assertThat(new ApiException("request failed", null)).hasMessage("request failed");
    }
}
