package gg.oddin.oddsfeed.examples;

import com.oddin.oddsfeedsdk.OddsFeedSession;
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent;
import com.oddin.oddsfeedsdk.mq.entities.BetCancel;
import com.oddin.oddsfeedsdk.mq.entities.BetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.BetStop;
import com.oddin.oddsfeedsdk.mq.entities.FixtureChange;
import com.oddin.oddsfeedsdk.mq.entities.Message;
import com.oddin.oddsfeedsdk.mq.entities.OddsChange;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetCancel;
import com.oddin.oddsfeedsdk.mq.entities.RollbackBetSettlement;
import com.oddin.oddsfeedsdk.mq.entities.UnparsableMessage;
import com.oddin.oddsfeedsdk.subscribe.OddsFeedListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A session's messages, logged one line each. A session's callbacks run one at a time and in order
 * on a thread of that session, and the SDK acknowledges a message after the callback returns: a
 * slow callback slows its own session, and no other.
 */
class LoggingListener implements OddsFeedListener {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingListener.class);

    @Override
    public void onOddsChange(OddsFeedSession session, OddsChange<SportEvent> message) {
        log("odds change", message.getEvent(), message);
    }

    @Override
    public void onBetStop(OddsFeedSession session, BetStop<SportEvent> message) {
        log("bet stop", message.getEvent(), message);
    }

    @Override
    public void onBetSettlement(OddsFeedSession session, BetSettlement<SportEvent> message) {
        log("bet settlement", message.getEvent(), message);
    }

    @Override
    public void onRollbackBetSettlement(OddsFeedSession session, RollbackBetSettlement<SportEvent> message) {
        log("rollback bet settlement", message.getEvent(), message);
    }

    @Override
    public void onBetCancel(OddsFeedSession session, BetCancel<SportEvent> message) {
        log("bet cancel", message.getEvent(), message);
    }

    @Override
    public void onRollbackBetCancel(OddsFeedSession session, RollbackBetCancel<SportEvent> message) {
        log("rollback bet cancel", message.getEvent(), message);
    }

    @Override
    public void onFixtureChange(OddsFeedSession session, FixtureChange<SportEvent> message) {
        log("fixture change", message.getEvent(), message);
    }

    @Override
    public void onUnparsableMessage(OddsFeedSession session, UnparsableMessage<SportEvent> message) {
        log("unparsable message", message.getEvent(), message);
    }

    private static void log(String what, SportEvent event, Message message) {
        LOG.info(
                "{} of {} from producer {}",
                what,
                event.getId(),
                message.getProducer().getId());
    }
}
