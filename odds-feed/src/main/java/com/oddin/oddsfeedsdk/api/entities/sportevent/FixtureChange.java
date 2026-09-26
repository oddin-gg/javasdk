package com.oddin.oddsfeedsdk.api.entities.sportevent;

import com.oddin.oddsfeedsdk.schema.utils.URN;
import java.util.Date;

public interface FixtureChange {
    URN getSportEventId();

    Date getUpdateTime();
}
