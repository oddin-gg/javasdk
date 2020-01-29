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

### Current limitations
* Replay manager not implemented, but is present in API
* Match status (SportEventStatus) is not implemented and missing in API
* Fixture streams are missing in API
 