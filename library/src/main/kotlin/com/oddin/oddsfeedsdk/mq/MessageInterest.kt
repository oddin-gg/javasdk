package com.oddin.oddsfeedsdk.mq

import com.oddin.oddsfeedsdk.api.entities.Producer
import com.oddin.oddsfeedsdk.api.entities.ProducerScope

enum class MessageInterest(val routingKeys: List<String>) {
    LIVE_ONLY(arrayListOf("*.*.live.*.*.*.*")),
    PREMATCH_ONLY(arrayListOf("*.pre.*.*.*.*.*")),
    HI_PRIORITY_ONLY(arrayListOf("hi.*.*.*.*.*.*")),
    LOW_PRIORITY_ONLY(arrayListOf("lo.*.*.*.*.*.*")),
    SPECIFIED_MATCHES_ONLY(emptyList()),
    ALL(arrayListOf("*.*.*.*.*.*.*")),
    SYSTEM_ALIVE_ONLY(arrayListOf("-.-.-.alive.#"));

    open fun getPossibleSourceProducers(availableProducers: Map<Long, Producer>): Set<Long> {
        val possibleProducers = mutableSetOf<Long>()
        when (this) {
            LIVE_ONLY -> possibleProducers.addAll(availableProducers.filterValues {
                it.producerScopes.contains(
                    ProducerScope.LIVE
                )
            }.keys)

            PREMATCH_ONLY -> possibleProducers.addAll(availableProducers.filterValues {
                it.producerScopes.contains(
                    ProducerScope.PREMATCH
                )
            }.keys)

            else -> possibleProducers.addAll(availableProducers.keys)
        }
        return possibleProducers
    }

    fun isProducerInScope(producer: Producer): Boolean {
        return when (this) {
            LIVE_ONLY -> producer.producerScopes.contains(ProducerScope.LIVE)
            PREMATCH_ONLY -> producer.producerScopes.contains(ProducerScope.PREMATCH)
            else -> true
        }
    }
}
