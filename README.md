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

### Current limitations
* Match status (SportEventStatus) is not implemented and missing in API
 