package com.oddin.oddsfeedsdk.cache.entity

import com.oddin.oddsfeedsdk.api.ApiClient
import com.oddin.oddsfeedsdk.api.entities.sportevent.UnderageStatus
import com.oddin.oddsfeedsdk.config.OddsFeedConfiguration
import com.oddin.oddsfeedsdk.schema.rest.v1.RAPlayerProfileEndpoint
import com.oddin.oddsfeedsdk.schema.utils.URN
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PlayerCacheUnderageTest {
    private val config = mockk<OddsFeedConfiguration> {
        every { maxPlayerCacheSize } returns 100
        every { exceptionHandlingStrategy } returns com.oddin.oddsfeedsdk.config.ExceptionHandlingStrategy.CATCH
    }

    private fun player(id: String, underage: Int?) = RAPlayerProfileEndpoint.Player().apply {
        this.id = id
        this.name = "Player"
        this.sportID = "od:sport:1"
        this.underage = underage
    }

    @Test
    fun `wire encoding maps onto the enum and a missing attribute reads as unknown`() {
        assertEquals(UnderageStatus.YES, UnderageStatus.fromValue(1))
        assertEquals(UnderageStatus.NO, UnderageStatus.fromValue(0))
        assertEquals(UnderageStatus.UNKNOWN, UnderageStatus.fromValue(-1))
        assertEquals(UnderageStatus.UNKNOWN, UnderageStatus.fromValue(null))
        assertEquals(UnderageStatus.UNKNOWN, UnderageStatus.fromValue(7))
    }

    @Test
    fun `player cache carries underage from the profile`() {
        val api = mockk<ApiClient>(relaxed = true)
        val yes = URN.parse("od:player:1")
        val missing = URN.parse("od:player:2")
        coEvery { api.fetchPlayerProfile(yes, any()) } returns player("od:player:1", 1)
        coEvery { api.fetchPlayerProfile(missing, any()) } returns player("od:player:2", null)
        val cache = PlayerCacheImpl(api, config)

        assertEquals(UnderageStatus.YES, cache.getPlayer(yes, setOf(Locale.ENGLISH))?.underage)
        assertEquals(UnderageStatus.UNKNOWN, cache.getPlayer(missing, setOf(Locale.ENGLISH))?.underage)
    }
}
