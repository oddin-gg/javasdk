package com.oddin.oddsfeedsdk.api.entities.sportevent

import com.oddin.oddsfeedsdk.schema.utils.URN
import java.util.*

interface Player {
    val id: URN?
    val names: Map<Locale, String>?
    fun getName(locale: Locale): String?
    val fullNames: Map<Locale, String>?
    fun getFullName(locale: Locale): String?
}
