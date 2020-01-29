package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface SportSummary {
    val id: URN
    val names: Map<Locale, String>?
    fun getName(locale: Locale): String?
}

interface Sport: SportSummary {
    val tournaments: List<Tournament>?
}