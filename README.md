Java SDK
----------------

Purpose of this SDK is to make integration process much smoother and easier. This SDK should take care of all connection, 
data binding and other issues related to connection to API and Feed.

### How to start

Please implement two basic interfaces:
* OddsFeedListener
* GlobalEventsListener


```java
OddsFeedListenerImplementation listener = new OddsFeedListenerImplementation();
GlobalEventsListenerImplementation globalEventsListener = new GlobalEventsListenerImplementation();

OddsFeedConfiguration config = OddsFeed.getConfigurationBuilder().setAccessToken("your-token").build();

OddsFeed oddsFeed = new OddsFeed(globalEventsListener, config);

OddsFeedSessionBuilder sessionBuilder = oddsFeed.getSessionBuilder();
sessionBuilder.setListener(listener).setMessageInterest(MessageInterest.AllMessages).build();

oddsFeed.open();
```

You are all set and messages should start coming.

You can check more information via appropriate managers - SportsInfoManager, MarketDescriptionManager, ReplayManager and others
For example:
```java
SportsInfoManager sportsInfoManager = oddsFeed.getSportsInfoManager();
// Fetch all sports with default locale
for (Sport sport : sportsInfoManager.getSports()) {

}

// Fetch all active tournaments with default locale
for (SportEvent tournament : sportsInfoManager.getActiveTournaments("Dota 2")) {

}
```

### Cache configuration (memory)

The SDK keeps sport events, competitors and players in in-memory caches, bounded
by both time and a maximum entry count. Over-capacity entries are evicted
least-recently-used and re-fetched from the API on next access, so there is no
functional data loss.

Defaults: match 10 000, fixture 10 000, competitor 20 000, player 50 000.

If you consume a very large schedule and see high memory use, lower the caps:

```java
OddsFeedConfiguration config = OddsFeed.getConfigurationBuilder()
        .setAccessToken("your-token")
        .setMaxMatchCacheSize(2000)
        .setMaxFixtureCacheSize(2000)
        .setMaxCompetitorCacheSize(4000)
        .setMaxPlayerCacheSize(8000)
        .build();
```

### Replay

You can use replay feature to receive data from previously played events. You need to build a replay session via session builder, add events to replay list and play it.

```java
// Set up your odds feed config
// Build replay session
OddsFeedSessionBuilder sessionBuilder = oddsFeed.getSessionBuilder();
sessionBuilder.setListener(listener).buildReplay();

oddsFeed.open();

oddsFeed.getReplayManager().addSportEvent(URN.parse("od:match:1"));

// Start receiving odds on your listener
oddsFeed.getReplayManager().play();

// Stop replay
oddsFeed.getReplayManager().stop();

// Stop and clear replay list
oddsFeed.getReplayManager().clear();

```

You should start receiving event odds via provided listener.

 
