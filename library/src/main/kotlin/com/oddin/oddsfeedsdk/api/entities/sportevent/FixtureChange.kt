package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*


interface FixtureChange {
    val sportEventId: URN
    val updateTime: Date
}

data class FixtureChangeImpl(override val sportEventId: URN, override val updateTime: Date) : FixtureChange