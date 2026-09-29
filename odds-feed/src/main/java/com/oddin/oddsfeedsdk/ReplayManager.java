package com.oddin.oddsfeedsdk;

import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The replay server's list of events, and playing it. */
public interface ReplayManager {
    @Nullable
    List<SportEvent> getReplayList();

    boolean addSportEvent(SportEvent event);

    boolean addSportEvent(URN id);

    boolean removeSportEvent(SportEvent event);

    boolean removeSportEvent(URN id);

    boolean play();

    boolean play(int speed, int maxDelayInMs);

    boolean play(int speed, int maxDelayInMs, boolean runParallel);

    boolean play(int speed, int maxDelayInMs, String producer, boolean rewriteTimestamps);

    boolean play(int speed, int maxDelayInMs, String producer, boolean rewriteTimestamps, boolean runParallel);

    boolean stop();

    boolean clear();
}
