package dev.steamshelf.server

import dev.steamshelf.server.routes.hxTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AuthPartsTest {
    private var now = Instant.parse("2026-11-12T10:00:00Z").toEpochMilli()
    private val guard = NonceGuard { now }

    @Test
    fun `a fresh nonce is accepted once`() {
        assertTrue(guard.accept("2026-11-12T09:59:30Zabc"))
        assertFalse(guard.accept("2026-11-12T09:59:30Zabc"))
        assertTrue(guard.accept("2026-11-12T09:59:30Zdef"))
    }

    @Test
    fun `stale, future and malformed nonces are refused`() {
        assertFalse(guard.accept("2026-11-12T09:50:00Zold"))
        assertFalse(guard.accept("2026-11-12T10:10:00Zsoon"))
        assertFalse(guard.accept("not a nonce at all!!"))
        assertFalse(guard.accept(null))
    }

    @Test
    fun `the browser's language picks the store language and country`() {
        assertEquals("spanish" to "ES", storeDefaultsFor("es-ES,es;q=0.9,en;q=0.8"))
        assertEquals("latam" to "AR", storeDefaultsFor("es-AR"))
        assertEquals("german" to "US", storeDefaultsFor("de"))
        assertEquals("brazilian" to "BR", storeDefaultsFor("pt-BR"))
        assertEquals("english" to "US", storeDefaultsFor(null))
        assertEquals("english" to "US", storeDefaultsFor("*"))
    }

    @Test
    fun `HX-Trigger values stay ASCII`() {
        assertEquals("{\"toast\":\"Saved \\u2014 \\\"ok\\\"\"}", hxTrigger(toast = "Saved — \"ok\""))
        assertEquals("""{"dialog-close":true,"toast":"x"}""", hxTrigger(closeDialog = true, toast = "x"))
    }

    @Test
    fun `formatting matches the app`() {
        assertEquals("0 h", Format.hours(0.0))
        assertEquals("45 min", Format.hours(0.75))
        assertEquals("2.5 h", Format.hours(2.5))
        assertEquals("1,234 h", Format.hours(1234.4))
        assertEquals("1.5K", Format.count(1_500))
        assertEquals("12K", Format.count(12_345))
        assertEquals("Never played", Format.minutes(0))
    }
}
