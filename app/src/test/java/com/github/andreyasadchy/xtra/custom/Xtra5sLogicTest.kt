package com.github.andreyasadchy.xtra.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Xtra5s custom: checks the logic behind the custom features, so an official Xtra update that
 * breaks them is held back by the release workflow instead of being published.
 */
class Xtra5sLogicTest {

    // ---- Volume curve (Side-By-Side and the normal player) ----

    @Test
    fun volumeCurveEnds() {
        assertEquals(0f, SoloVolume.gain(0f), 0.0001f)
        assertEquals(1f, SoloVolume.gain(1f), 0.0001f)
        assertEquals(0f, SoloVolume.gain(-1f), 0.0001f)
        assertEquals(1f, SoloVolume.gain(2f), 0.0001f)
    }

    @Test
    fun volumeNumberMatchesLoudness() {
        // Heard loudness ~ level^0.6: the slider number should be what you hear.
        for (percent in listOf(10, 30, 50, 70, 90)) {
            val heard = SoloVolume.gain(percent / 100f).toDouble().pow(0.6) * 100
            assertEquals("$percent%", percent.toDouble(), heard, 0.5)
        }
    }

    @Test
    fun volumeReadBackUndoesCurve() {
        for (percent in 0..100 step 5) {
            val level = percent / 100f
            assertEquals(level, SoloVolume.level(SoloVolume.gain(level)), 0.001f)
        }
    }

    // ---- Side-By-Side ----

    @Test
    fun channelNamesAreCleaned() {
        assertEquals("name", SideBySide.cleanLogin("@Name"))
        assertEquals("xqc", SideBySide.cleanLogin("  xQc  "))
        assertEquals("some_one", SideBySide.cleanLogin("https://www.twitch.tv/Some_One?ref=abc"))
        assertEquals("abc", SideBySide.cleanLogin("twitch.tv/abc/videos"))
        assertEquals("", SideBySide.cleanLogin("   "))
    }

    @Test
    fun qualities() {
        assertEquals("720p", SideBySide.DEFAULT_QUALITY)
        assertTrue(SideBySide.QUALITIES.contains(SideBySide.DEFAULT_QUALITY))
        assertFalse(SideBySide.QUALITIES.any { it.contains("Audio", true) })
        assertEquals(Int.MAX_VALUE, SideBySide.targetHeight(SideBySide.SOURCE))
        assertEquals(1080, SideBySide.targetHeight("1080p"))
        assertEquals(720, SideBySide.targetHeight("720p"))
        assertEquals(160, SideBySide.targetHeight("160p"))
    }

    // ---- Highlights ----

    @Test
    fun highlightColors() {
        assertEquals(0x59112233, UserHighlights.parseColor("#112233")) // default transparency
        assertEquals(0x80112233.toInt(), UserHighlights.parseColor("#80112233"))
        assertEquals(0xFFFF0000.toInt(), UserHighlights.parseColor(" #FFFF0000 "))
        assertNull(UserHighlights.parseColor("#12345"))
        assertNull(UserHighlights.parseColor("nothex"))
        assertNull(UserHighlights.parseColor(null))
        assertEquals("#80112233", UserHighlights.toHex(0x80112233.toInt()))
    }

    @Test
    fun highlightUserList() {
        val entries = UserHighlights.parseEntries("@Foo#FFFF0000, bar  baz;foo\nQux")
        assertEquals(listOf("Foo", "bar", "baz", "Qux"), entries.map { it.first })
        assertEquals(0xFFFF0000.toInt(), entries[0].second)
        assertNull(entries[1].second)
        val again = UserHighlights.parseEntries(UserHighlights.serializeEntries(entries))
        assertEquals(entries, again)
    }

    @Test
    fun highlightGroupsRoundTrip() {
        val groups = listOf(
            UserHighlights.Group("Mods | team", 0xFF00FF00.toInt(), mutableListOf("a", "b_c")),
            UserHighlights.Group("Friends", 0x59112233, mutableListOf()),
        )
        val parsed = UserHighlights.parseGroups(UserHighlights.serializeGroups(groups))
        assertEquals(groups.map { it.name }, parsed.map { it.name })
        assertEquals(groups.map { it.color }, parsed.map { it.color })
        assertEquals(groups.map { it.members }, parsed.map { it.members })
        assertTrue(UserHighlights.parseGroups(null).isEmpty())
        assertTrue(UserHighlights.parseGroups("broken line").isEmpty())
    }
}
