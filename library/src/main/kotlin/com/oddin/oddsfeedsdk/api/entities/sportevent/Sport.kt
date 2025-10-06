package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface SportSummary {
    val id: URN
    @Deprecated("This attribute is deprecated and will be removed in future.")
    val refId: URN?
    val names: Map<Locale, String>?
    fun getName(locale: Locale): String?
    fun getAbbreviation(locale: Locale): String?
    fun getIconPath(locale: Locale): String?
}

interface Sport: SportSummary {
    val tournaments: List<Tournament>?
}