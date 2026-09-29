package com.oddin.oddsfeedsdk.api;

import java.util.Date;

/** Who the access token belongs to. */
public interface BookmakerDetail {
    /** When the access token expires. */
    Date getExpireAt();

    int getBookmakerId();

    /** The virtual host of the feed's broker for this bookmaker. */
    String getVirtualHost();
}
