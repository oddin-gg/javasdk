package com.oddin.oddsfeedsdk.subscribe

import com.oddin.oddsfeedsdk.mq.entities.ProducerStatus
import com.oddin.oddsfeedsdk.schema.utils.URN


interface GlobalEventsListener {
    fun onProducerStatusChange(producerStatus: ProducerStatus)
    fun onConnectionDown()
    fun onEventRecoveryCompleted(eventId: URN, requestId: Long)
}