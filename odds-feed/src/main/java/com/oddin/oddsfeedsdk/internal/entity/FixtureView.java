package com.oddin.oddsfeedsdk.internal.entity;

import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.FIXTURE;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.FIXTURE_EXTRA_INFO;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.START_TIME;
import static com.oddin.oddsfeedsdk.internal.entity.MatchFields.TV_CHANNELS;

import com.oddin.oddsfeedsdk.api.entities.sportevent.Fixture;
import com.oddin.oddsfeedsdk.api.entities.sportevent.TvChannel;
import com.oddin.oddsfeedsdk.internal.cache.Entry;
import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A match's fixture as the client holds it, loaded once in the default locale as 0.0.x loaded it.
 * A fixture without extra info or TV channels has none, as in 0.0.x, rather than null.
 */
final class FixtureView implements Fixture {

    private final Entities entities;
    private final URN id;

    FixtureView(Entities entities, URN id) {
        this.entities = entities;
        this.id = id;
    }

    @Override
    public @Nullable Date getStartTime() {
        return entities.guard(this, () -> Entities.date(fixture().get(START_TIME, null)));
    }

    @Override
    public @Nullable Map<String, String> getExtraInfo() {
        return entities.guard(this, () -> {
            Map<String, String> info = fixture().get(FIXTURE_EXTRA_INFO, null);
            return info == null ? Map.of() : info;
        });
    }

    @Override
    public @Nullable List<TvChannel> getTvChannels() {
        return entities.guard(this, () -> {
            List<TvChannel> channels = fixture().get(TV_CHANNELS, null);
            return channels == null ? List.of() : channels;
        });
    }

    @Override
    public String toString() {
        return "fixture of match " + id;
    }

    private Entry fixture() {
        return Entities.found(entities.matches.fixture(id), FIXTURE, entities.matches.defaultLocale(), this);
    }
}
