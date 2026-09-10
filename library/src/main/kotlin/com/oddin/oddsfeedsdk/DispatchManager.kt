package com.oddin.oddsfeedsdk

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.inject.Inject
import com.oddin.oddsfeedsdk.mq.MessageInterest
import com.oddin.oddsfeedsdk.mq.RoutingKeyInfo
import com.oddin.oddsfeedsdk.mq.entities.BasicMessage
import com.oddin.oddsfeedsdk.mq.entities.MessageTimestamp
import com.oddin.oddsfeedsdk.mq.entities.UnparsedMessage
import io.reactivex.Observable
import io.reactivex.Scheduler
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class FeedMessage(
    val message: BasicMessage?,
    val rawMessage: ByteArray?,
    val routingKey: RoutingKeyInfo,
    val timestamp: MessageTimestamp
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FeedMessage

        if (message != other.message) return false
        if (rawMessage != null) {
            if (other.rawMessage == null) return false
            if (!rawMessage.contentEquals(other.rawMessage)) return false
        } else if (other.rawMessage != null) return false
        if (routingKey != other.routingKey) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = message?.hashCode() ?: 0
        result = 31 * result + (rawMessage?.contentHashCode() ?: 0)
        result = 31 * result + routingKey.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }

}

data class RawFeedMessage(
    val message: UnparsedMessage,
    val rawMessage: ByteArray,
    val messageInterest: MessageInterest,
    val routingKey: RoutingKeyInfo,
    val timestamp: MessageTimestamp
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RawFeedMessage

        if (message != other.message) return false
        if (!rawMessage.contentEquals(other.rawMessage)) return false
        if (messageInterest != other.messageInterest) return false
        if (routingKey != other.routingKey) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = message.hashCode()
        result = 31 * result + rawMessage.contentHashCode()
        result = 31 * result + messageInterest.hashCode()
        result = 31 * result + routingKey.hashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

interface DispatchManager {
    fun publish(event: Any)
    fun <T> listen(eventType: Class<T>): Observable<T>
    fun close()
}

class DispatchManagerImpl @Inject constructor() : DispatchManager {
    private val scheduler: Scheduler
    private val executor: ExecutorService
    private val publisher = PublishSubject.create<Any>()

    init {
        val namedThreadFactory =
            ThreadFactoryBuilder().setNameFormat("pool-t-%d")
                .build()
        executor = Executors.newFixedThreadPool(3, namedThreadFactory)
        scheduler = Schedulers.from(executor)
    }

    override fun publish(event: Any) = publisher.onNext(event)

    override fun <T> listen(eventType: Class<T>): Observable<T> = publisher.ofType(eventType).subscribeOn(scheduler)

    override fun close() {
        publisher.onComplete()
        scheduler.shutdown()
        executor.shutdownNow()
    }
}
