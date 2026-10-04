package com.oddin.oddsfeedsdk.internal.replay;

import com.oddin.oddsfeedsdk.ReplayManager;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy;
import com.oddin.oddsfeedsdk.internal.rest.ApiClient;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAReplayEvent;
import com.oddin.oddsfeedsdk.schema.rest.v1.RAReplaySetContent;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client's side of {@link ReplayManager}: the replay server's list of events and its controls,
 * over the REST client, with 0.0.x's answers. A call the API fails is logged and answers false, or
 * null for the list, whatever the exception handling strategy, as 0.0.x answered. A control call is
 * not repeated after a server error or a lost connection, since the API may have carried it out
 * already.
 *
 * <p>Turning the list's ids into events - the id itself and the {@link SportEventFactory} - follows
 * the strategy, as every getter does: under {@code THROW} the first failure fails the list, under
 * {@code CATCH} the list is null, never short. 0.0.x threw for an id that is no URN under either
 * strategy, and left out an event it failed to build under {@code CATCH}.
 *
 * <p>The {@link ApiClient} adds the node id to every call. Safe for concurrent use.
 */
public final class Replay implements ReplayManager {

    private static final Logger LOG = LoggerFactory.getLogger(Replay.class);

    private final ApiClient api;
    private final SportEventFactory events;
    private final ExceptionHandlingStrategy strategy;

    public Replay(ApiClient api, SportEventFactory events, ExceptionHandlingStrategy strategy) {
        this.api = api;
        this.events = events;
        this.strategy = strategy;
    }

    /**
     * The events on the replay list, in the API's order; a new list each time. An entry without an
     * id is left out, as 0.0.x left it out.
     */
    @Override
    public @Nullable List<SportEvent> getReplayList() {
        RAReplaySetContent content;
        try {
            content = api.fetchReplaySetContent();
        } catch (RuntimeException e) {
            LOG.error("Failed to fetch replay events", e);
            return null;
        }
        try {
            var list = new ArrayList<SportEvent>();
            for (RAReplayEvent event : content.getEvent()) {
                @Nullable String id = event.getId();
                if (id != null) {
                    list.add(events.build(URN.parse(id)));
                }
            }
            return list;
        } catch (RuntimeException e) {
            if (strategy == ExceptionHandlingStrategy.THROW) {
                throw e;
            }
            LOG.error("Failed to build replay events", e);
            return null;
        }
    }

    /** False without asking the API when the event has no id. */
    @Override
    public boolean addSportEvent(SportEvent event) {
        URN id = event.getId();
        return id != null && addSportEvent(id);
    }

    @Override
    public boolean addSportEvent(URN id) {
        try {
            api.putReplayEvent(id);
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to add event id {}", id, e);
            return false;
        }
    }

    /** False without asking the API when the event has no id. */
    @Override
    public boolean removeSportEvent(SportEvent event) {
        URN id = event.getId();
        return id != null && removeSportEvent(id);
    }

    @Override
    public boolean removeSportEvent(URN id) {
        try {
            api.deleteReplayEvent(id);
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to remove event id {}", id, e);
            return false;
        }
    }

    /** Plays with the API's defaults for everything. */
    @Override
    public boolean play() {
        return start(null, null, null, null, null);
    }

    @Override
    public boolean play(int speed, int maxDelayInMs) {
        return start(speed, maxDelayInMs, null, null, null);
    }

    @Override
    public boolean play(int speed, int maxDelayInMs, boolean runParallel) {
        return start(speed, maxDelayInMs, null, runParallel, null);
    }

    @Override
    public boolean play(int speed, int maxDelayInMs, String producer, boolean rewriteTimestamps) {
        return start(speed, maxDelayInMs, rewriteTimestamps, null, producer);
    }

    @Override
    public boolean play(int speed, int maxDelayInMs, String producer, boolean rewriteTimestamps, boolean runParallel) {
        return start(speed, maxDelayInMs, rewriteTimestamps, runParallel, producer);
    }

    @Override
    public boolean stop() {
        try {
            api.postReplayStop();
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to stop replay", e);
            return false;
        }
    }

    @Override
    public boolean clear() {
        try {
            api.postReplayClear();
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to clear replay", e);
            return false;
        }
    }

    /** What is null is not sent, and the API uses its default. */
    private boolean start(
            @Nullable Integer speed,
            @Nullable Integer maxDelay,
            @Nullable Boolean rewriteTimestamps,
            @Nullable Boolean runParallel,
            @Nullable String producer) {
        try {
            api.postReplayStart(speed, maxDelay, rewriteTimestamps, runParallel, producer);
            return true;
        } catch (RuntimeException e) {
            LOG.error("Failed to play replay", e);
            return false;
        }
    }
}
