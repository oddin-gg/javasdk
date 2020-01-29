package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface Player {
    val id: URN?
    val names: Map<Locale, String>?
    fun getName(locale: Locale): String?
}

interface Competitor : Player {
    val countries: Map<Locale, String>?
    val abbreviations: Map<Locale, String>?
    val virtual: Boolean?
    val countryCode: String?

    fun getCountry(locale: Locale): String?
    fun getAbbreviation(locale: Locale): String?
}



