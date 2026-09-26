package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.Producer;
import org.jspecify.annotations.Nullable;

public interface Message {
    @Nullable Producer getProducer();

    MessageTimestamp getTimestamp();
}
