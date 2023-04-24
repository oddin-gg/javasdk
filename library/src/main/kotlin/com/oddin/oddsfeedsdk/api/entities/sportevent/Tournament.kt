package com.oddin.oddsfeedsdk.api.entities.sportevent

import java.util.*


interface LongTermEvent : SportEvent {
    val sport: SportSummary?
}

interface Tournament : LongTermEvent {
    val competitors: List<Competitor>?
    val startDate: Date?
    val endDate: Date?
    val riskTier: Int?
    fun getAbbreviation(locale: Locale): String?
}