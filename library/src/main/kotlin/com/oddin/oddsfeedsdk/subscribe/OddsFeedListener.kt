package com.oddin.oddsfeedsdk.subscribe

import com.oddin.oddsfeedsdk.OddsFeedSession
import com.oddin.oddsfeedsdk.api.entities.sportevent.SportEvent
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.*
import java.net.URI

interface BaseOddsFeedListener<T : SportEvent> {
    fun onOddsChange(session: OddsFeedSession, message: OddsChange<T>)
    fun onBetStop(session: OddsFeedSession, message: BetStop<T>)
    fun onBetSettlement(session: OddsFeedSession, message: BetSettlement<T>)
    fun onRollbackBetSettlement(session: OddsFeedSession, message: RollbackBetSettlement<T>)
    fun onRollbackBetCancel(session: OddsFeedSession, message: RollbackBetCancel<T>)
    fun onBetCancel(session: OddsFeedSession, message: BetCancel<T>)
    fun onFixtureChange(session: OddsFeedSession, message: FixtureChange<T>)
    fun onUnparsableMessage(session: OddsFeedSession, message: UnparsableMessage<T>){}
}

interface OddsFeedListener : BaseOddsFeedListener<SportEvent>

interface OddsFeedExtListener {
    fun onRawFeedMessageReceived(
        message: UnparsedMessage,
        messageInterest: MessageInterest,
        routingKey: RoutingKeyInfo,
        timestamp: MessageTimestamp
    )

    fun onRawApiDataReceived(uri: URI, data: Any)
}
