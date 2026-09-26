package com.oddin.oddsfeedsdk.mq.entities;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import org.jspecify.annotations.Nullable;

public interface UnparsableMessage<T extends SportEvent> extends Message {
    T getEvent();

    byte @Nullable [] getRawMessage();
}
