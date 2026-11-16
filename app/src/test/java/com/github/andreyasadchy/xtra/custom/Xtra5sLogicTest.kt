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

    @Test
    fun recentPairsIgnoreOrder() {
        // The same two channels swapped round are one entry: the newest order wins, at the top.
        var list = SideBySide.withRecent(emptyList(), "a", "b")
        list = SideBySide.withRecent(list, "c", "d")
        list = SideBySide.withRecent(list, "b", "a")
        assertEquals(listOf("b" to "a", "c" to "d"), list)
        // Older saved lists with both orders are merged (newest kept).
        assertEquals(listOf("x" to "y", "p" to "q"), SideBySide.parseRecent("x,y\np,q\ny,x\nbroken\n,z"))
        // At most 8 kept.
        var many = emptyList<Pair<String, String>>()
        for (i in 1..12) many = SideBySide.withRecent(many, "s$i", "t$i")
        assertEquals(8, many.size)
        assertEquals("s12" to "t12", many.first())
    }

    // ---- Chat screen list ----

    @Test
    fun chatScreenFollowsNotices() {
        val screen = ChatScreen.newList<String>()
        // New messages go at the end; the chat's limit drops the oldest.
        assertEquals(0 to 0, ChatScreen.onNew(screen, "a", 0))
        assertEquals(1 to 0, ChatScreen.onNew(screen, "b", 0))
        assertEquals(2 to 1, ChatScreen.onNew(screen, "c", 1))
        assertEquals(listOf("b", "c"), screen)
        // History loaded when joining goes on top.
        assertTrue(ChatScreen.onHistory(screen, listOf("x", "y")))
        assertEquals(listOf("x", "y", "b", "c"), screen)
        // Cleared.
        assertEquals(4, ChatScreen.onClear(screen))
        assertTrue(screen.isEmpty())
        assertEquals(0, ChatScreen.onClear(screen))
    }

    @Test
    fun chatScreenResyncDoesNotDuplicate() {
        // The background thread has already added "c" (and its notice arrives after the copy).
        val source = mutableListOf("a", "b", "c")
        val screen = ChatScreen.newList<String>()
        ChatScreen.resync(screen, source)
        assertEquals(listOf("a", "b", "c"), screen)
        // Its notice: not added again, but the drop from the top still happens.
        assertEquals(-1 to 1, ChatScreen.onNew(screen, source[2], 1))
        assertEquals(listOf("b", "c"), screen)
        // History already copied by a resync isn't added twice.
        val history = mutableListOf("h1", "h2")
        val withHistory = ChatScreen.newList<String>()
        ChatScreen.resync(withHistory, (history + listOf("m")).toMutableList())
        assertFalse(ChatScreen.onHistory(withHistory, history))
        assertEquals(listOf("h1", "h2", "m"), withHistory)
    }

    @Test
    fun chatScreenUsesSameMessageNotEqualText() {
        // Two different messages with the same text are both shown.
        val first = StringBuilder("hi")
        val second = StringBuilder("hi")
        val screen = ChatScreen.newList<StringBuilder>()
        ChatScreen.onNew(screen, first, 0)
        assertEquals(1 to 0, ChatScreen.onNew(screen, second, 0))
        assertEquals(2, screen.size)
    }

    // ---- Split-screen Dual Stream (Companion) ----

    @Test
    fun qualityOncePicksClosest() {
        val q = { name: String, h: Int?, fps: Float? -> com.github.andreyasadchy.xtra.model.VideoQuality(name, h, fps) }
        val list = listOf(q("auto", null, null), q("1080p60", 1080, 60f), q("720p60", 720, 60f), q("720p", 720, 30f), q("480p", 480, 30f), q("160p", 160, 30f), q("audio_only", null, null))
        assertEquals("720p60", AudioOnlyOnce.choose(list, "720p")?.name)
        assertEquals("1080p60", AudioOnlyOnce.choose(list, SideBySide.SOURCE)?.name)
        assertEquals("480p", AudioOnlyOnce.choose(list, "600p")?.name) // nothing exact: next lower
        assertEquals("160p", AudioOnlyOnce.choose(list, "100p")?.name) // nothing lower: the lowest
        assertEquals("audio_only", AudioOnlyOnce.choose(list, null)?.name)
    }

    @Test
    fun eachAppUpdatesItsOwnApk() {
        assertTrue(CompanionApp.isMyUpdate(false, "Xtra5s-1.9.0.apk"))
        assertFalse(CompanionApp.isMyUpdate(false, "Xtra5s-Companion-1.9.0.apk"))
        assertTrue(CompanionApp.isMyUpdate(true, "Xtra5s-Companion-1.9.0.apk"))
        assertFalse(CompanionApp.isMyUpdate(true, "Xtra5s-1.9.0.apk"))
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
