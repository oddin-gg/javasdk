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
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.bind.JAXBContext

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

    private val restContext = JAXBContext.newInstance("com.oddin.oddsfeedsdk.schema.rest.v1")

    private fun unmarshal(playerAttrs: String): RAPlayerProfileEndpoint.Player {
        val raw = """<?xml version="1.0" encoding="UTF-8"?>
            <player_profile generated_at="2026-09-25T12:00:00">
                <player id="od:player:1" name="P" sport="od:sport:1" $playerAttrs/>
            </player_profile>"""
        val endpoint = restContext.createUnmarshaller()
            .unmarshal(ByteArrayInputStream(raw.toByteArray())) as RAPlayerProfileEndpoint
        return endpoint.player
    }

    @Test
    fun `JAXB binds the underage attribute and leaves it null when absent`() {
        assertEquals(1, unmarshal("""underage="1"""").underage)
        assertEquals(0, unmarshal("""underage="0"""").underage)
        assertEquals(-1, unmarshal("""underage="-1"""").underage)
        assertEquals(null, unmarshal("").underage)
    }

    @Test
    fun `a payload without the attribute keeps a known value, an explicit -1 retracts it`() {
        val api = mockk<ApiClient>(relaxed = true)
        val id = URN.parse("od:player:1")
        coEvery { api.fetchPlayerProfile(id, Locale.ENGLISH) } returns player("od:player:1", 1)
        coEvery { api.fetchPlayerProfile(id, Locale.GERMAN) } returns player("od:player:1", null)
        coEvery { api.fetchPlayerProfile(id, Locale.FRENCH) } returns player("od:player:1", -1)
        val cache = PlayerCacheImpl(api, config)

        assertEquals(UnderageStatus.YES, cache.getPlayer(id, setOf(Locale.ENGLISH))?.underage)
        assertEquals(UnderageStatus.YES, cache.getPlayer(id, setOf(Locale.GERMAN))?.underage)
        assertEquals(UnderageStatus.UNKNOWN, cache.getPlayer(id, setOf(Locale.FRENCH))?.underage)
    }
}
