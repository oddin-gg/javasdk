package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import org.jspecify.annotations.Nullable;

public interface EventMessage<T extends SportEvent> extends Message {
    T getEvent();

    @Nullable Long getRequestId();

    byte[] getRawMessage();
}
