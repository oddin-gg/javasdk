package com.oddin.oddsfeedsdk.internal.replay;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.schema.utils.URN;

/**
 * Builds the sport event the client gets for an id in the replay list. 0.0.x built a match in the
 * default locale for every id, and loaded nothing until a getter was called.
 */
@FunctionalInterface
public interface SportEventFactory {

    /**
     * The event with this id.
     *
     * @throws RuntimeException when it cannot be built; the replay list then follows the exception
     *     handling strategy
     */
    SportEvent build(URN id);
}
