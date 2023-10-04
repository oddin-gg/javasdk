package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface Competitor {
    val id: URN?
    @Deprecated("This attribute is deprecated and will be removed in future.")
    val refId: URN?
    val names: Map<Locale, String>?
    fun getName(locale: Locale): String?
    val countries: Map<Locale, String>?
    val abbreviations: Map<Locale, String>?
    val virtual: Boolean?
    val countryCode: String?
    val underage: Int?
    val iconPath: String?

    fun getCountry(locale: Locale): String?
    fun getAbbreviation(locale: Locale): String?
}

interface TeamCompetitor: Competitor {
    val qualifier: String?
}



