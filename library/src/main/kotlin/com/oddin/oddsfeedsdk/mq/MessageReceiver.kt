package com.oddin.oddsfeedsdk.mq

import com.oddin.oddsfeedsdk.schema.utils.URN

data class RoutingKeyInfo(
    val fullRoutingKey: String,
    val sportId: URN?,
    val eventId: URN?,
    val isSystemRoutingKey: Boolean
    ) {

    fun hasValidURN(): Boolean {
        return sportId != null && eventId != null
    }
}