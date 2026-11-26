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

    // ---- VOD chapters menu ----

    @Test
    fun chapterTimes() {
        assertEquals("00:00", VodChapters.startText(0))
        assertEquals("03:12:03", VodChapters.startText(3 * 3600 + 12 * 60 + 3))
        assertEquals("12:05", VodChapters.startText(12 * 60 + 5))
        assertEquals("3hr 12m 02s", VodChapters.lengthText(3 * 3600 + 12 * 60 + 2))
        assertEquals("1hr 20m 56s", VodChapters.lengthText(3600 + 20 * 60 + 56))
        assertEquals("1hr 05m 00s", VodChapters.lengthText(3900))
        assertEquals("8m 40s", VodChapters.lengthText(8 * 60 + 40))
    }

    // ---- Chat commands (bots' public lists) ----

    @Test
    fun botCommandsParsed() {
        val nightbot = """{"_total":3,"status":200,"commands":[
            {"name":"!specs","message":"13900k | 4090","userLevel":"everyone","count":5},
            {"name":"!modonly","message":"secret","userLevel":"moderator"},
            {"name":"xd","message":"moon2M [user] [[count]]","userLevel":"everyone"}]}"""
        val nb = BotCommands.parseNightbot(nightbot)
        assertEquals(listOf("!specs", "xd"), nb.map { it.name })

        val se = """[
            {"command":"pc","aliases":["specs","rig"],"reply":"5800X3D, 32GB","enabled":true,"hidden":false,"accessLevel":100,"enabledOnline":true,"enabledOffline":true},
            {"command":"old","reply":"gone","enabled":false,"hidden":false,"accessLevel":100},
            {"command":"hide","reply":"x","enabled":true,"hidden":true,"accessLevel":100},
            {"command":"subs","reply":"x","enabled":true,"hidden":false,"accessLevel":250},
            {"command":"offlineonly","reply":"x","enabled":true,"hidden":false,"accessLevel":100,"enabledOnline":false,"enabledOffline":true}]"""
        assertEquals(listOf("!pc"), BotCommands.parseStreamElements(se, live = true).map { it.name })
        assertEquals(listOf("!pc", "!offlineonly"), BotCommands.parseStreamElements(se, live = false).map { it.name })
        assertEquals(listOf("!specs", "!rig"), BotCommands.parseStreamElements(se, live = true)[0].aliases)

        val fossa = """{"roles":[],"commands":[
            {"name":"cat","response":"meow","aliases":["kitty"],"role_ids":[],"enabled_online":true,"enabled_offline":true},
            {"name":"mods","response":"x","aliases":[],"role_ids":["CAEQERoCNzc"],"enabled_online":true,"enabled_offline":true},
            {"name":"weather","response":"$(weather)","aliases":[],"role_ids":[],"enabled_online":false,"enabled_offline":true},
            {"name":"empty","response":"","aliases":[],"role_ids":[],"enabled_online":true,"enabled_offline":true}]}"""
        assertEquals(listOf("!cat"), BotCommands.parseFossabot(fossa, live = true).map { it.name })
        assertEquals(listOf("!cat", "!weather"), BotCommands.parseFossabot(fossa, live = false).map { it.name })
    }

    @Test
    fun botCommandsMergedAndSearched() {
        val c = { name: String, reply: String, bot: String -> BotCommands.Command(name, emptyList(), reply, bot) }
        val merged = BotCommands.merge(listOf(
            listOf(c("!pc", "fossa pc", "F"), c("!Zoo", "z", "F")),
            listOf(c("!PC", "se pc", "S"), c("!cat", "meow", "S")),
            listOf(c("xd", "lol", "N")),
        ))
        // One row per name (earlier bots win), A-Z ignoring "!".
        assertEquals(listOf("!cat", "!pc", "xd", "!Zoo"), merged.map { it.name })
        assertEquals("fossa pc", merged[1].reply)
        assertEquals(listOf("!cat"), BotCommands.search(merged, "MEOW").map { it.name })
        assertEquals(listOf("!pc"), BotCommands.search(merged, "pc").map { it.name })
        assertEquals(merged, BotCommands.search(merged, "  "))
    }

    @Test
    fun botPlaceholdersFound() {
        val text = "Hi $(user), uptime [twitch [channel]] count \${count} [1/2]"
        val found = BotCommands.placeholders(text).map { text.substring(it) }
        assertEquals(listOf("$(user)", "[twitch [channel]]", "\${count}"), found)
    }

    // ---- Emotes in the commands table ----

    @Test
    fun botEmoteWordsFound() {
        val emotes = mapOf("moon2E" to 1, "Kappa" to 2, "peepoHas" to 3)
        val text = "moon2E I have annoyed my human Kappa  kappa peepoHas!"
        assertEquals(listOf("moon2E", "Kappa"), BotEmotes.find(text, emotes).map { text.substring(it) })
        assertEquals(listOf("peepoHas"), BotEmotes.find("🤘 peepoHas", emotes).map { "🤘 peepoHas".substring(it) })
        assertTrue(BotEmotes.find("nothing here", emptyMap<String, Int>()).isEmpty())
    }

    @Test
    fun botEmotesFirstSourceWins() {
        val map = BotEmotes.byName(listOf(listOf("a" to "twitch"), listOf("a" to "7tv", "b" to "7tv"), listOf("b" to "bttv", "" to "x"))) { it.first }
        assertEquals(mapOf("a" to ("a" to "twitch"), "b" to ("b" to "7tv")), map)
    }

    @Test
    fun twitchEmotesFromGraphQL() {
        val body = """{"data":{"user":{"subscriptionProducts":[{"emotes":[{"id":"301","token":"moon2E"},{"id":"302","token":"moon2M"}]},{"emotes":[]}]}}}"""
        val emotes = BotEmotes.parseTwitchEmotes(body)
        assertEquals(listOf("moon2E", "moon2M"), emotes.map { it.name })
        assertEquals("https://static-cdn.jtvnw.net/emoticons/v2/301/default/dark/3.0", emotes[0].url4x)
        assertTrue(BotEmotes.parseTwitchEmotes("""{"data":{"user":null}}""").isEmpty())
    }

    // ---- Automatic refresh switches ----

    @Test
    fun autoRefreshSwitches() {
        val keys = AutoRefreshSettings.items.map { it.key }
        assertEquals(keys.size, keys.toSet().size) // one switch per behaviour
        assertEquals("All on", AutoRefreshSettings.summary(13, 13))
        assertEquals("All off", AutoRefreshSettings.summary(0, 13))
        assertEquals("11 of 13 on", AutoRefreshSettings.summary(11, 13))
    }

    // ---- Hold-and-drag seek (VODs) ----

    @Test
    fun holdSeekSteps() {
        val long = 100_000L // far from both ends
        val step = 60f // 3% of a 2000 px wide screen
        val dead = 40f
        fun j(dx: Float, edge: Boolean = false) = HoldSeek.jumpFor(dx, step, dead, edge, long, long)
        assertEquals(0L, j(0f))
        assertEquals(0L, j(-30f)) // back near the start = cancel
        assertEquals(-2L, j(-45f))
        assertEquals(5L, j(100f)) // each step 60 px: 40 + 60
        assertEquals(10L, j(160f))
        assertEquals(-60L, j(-400f)) // 1m after 6 steps
        assertEquals(1200L, j(40f + 14 * 60f)) // 20m after 14 steps (about 45% of the screen)
        assertEquals(3600L, j(40f + 17 * 60f))
        assertEquals(long, j(300f, edge = true)) // at the edge: End
        assertEquals(-long, j(-300f, edge = true)) // Start
        // Longer drags never jump less, and every step that fits can be reached in order.
        val jumps = (0..4000).map { j(it.toFloat()) }
        assertEquals(jumps, jumps.sorted())
        assertEquals(HoldSeek.STEPS.filter { it < long }.toList(), jumps.filter { it > 0 }.distinct())
        // Near the start of the VOD: it stays at the biggest step that fits.
        assertEquals(-30L, HoldSeek.jumpFor(-1500f, step, dead, false, 40, long))
        assertEquals(-40L, HoldSeek.jumpFor(-1500f, step, dead, true, 40, long))
        assertEquals(0L, HoldSeek.jumpFor(-500f, step, dead, false, 0, long)) // nothing to rewind
        // Start / End: on in the last 200 px, off only 60 px back out (no flicker), never when
        // the drag started in that zone, and only on the side you're dragging to.
        val w = 2000f
        assertFalse(HoldSeek.edgeShowing(250f, 900f, -650f, w, false))
        assertTrue(HoldSeek.edgeShowing(190f, 900f, -710f, w, false))
        assertTrue(HoldSeek.edgeShowing(240f, 900f, -660f, w, true)) // wobbled out a little: stays
        assertFalse(HoldSeek.edgeShowing(270f, 900f, -630f, w, true)) // clearly back out
        assertFalse(HoldSeek.edgeShowing(50f, 150f, -100f, w, false)) // started in the zone
        assertTrue(HoldSeek.edgeShowing(1850f, 900f, 950f, w, false)) // End side
        assertFalse(HoldSeek.edgeShowing(190f, 900f, 50f, w, false)) // dragging right: not Start
        assertEquals(1f, HoldSeek.fill(-40, 40))
        assertTrue(HoldSeek.fill(2, long) < HoldSeek.fill(10, long))
        assertEquals("2s", HoldSeek.label(-2))
        assertEquals("1m 30s", HoldSeek.label(90))
        assertEquals("1h 30m", HoldSeek.label(5400))
    }

    // ---- Muted parts (VODs) ----

    @Test
    fun mutedPartsMerged() {
        // Twitch splits muted parts into 3-minute pieces: touching ones become one part.
        val body = """{"data":{"video":{"muteInfo":{"mutedSegmentConnection":{"nodes":[
            {"duration":180,"offset":360},{"duration":180,"offset":0},{"duration":180,"offset":180},
            {"duration":182,"offset":2522},{"duration":180,"offset":3785}]}}}}}"""
        val parts = MutedParts.parse(body)
        assertEquals(listOf(0L until 540_000L, 2_522_000L until 2_704_000L, 3_785_000L until 3_965_000L), parts)
        assertEquals(0L until 540_000L, MutedParts.partAt(parts, 200_000))
        assertNull(MutedParts.partAt(parts, 600_000))
        assertEquals(540_000L, MutedParts.partAt(parts, 539_999)!!.last + 1) // skip lands where the sound returns
        assertTrue(MutedParts.parse("""{"data":{"video":{"muteInfo":null}}}""").isEmpty())
    }

    // ---- Update check ----

    @Test
    fun updateComparesVersions() {
        assertEquals(listOf(1, 66, 0), UpdateCheck.parse("xtra5s-v1.66.0"))
        assertNull(UpdateCheck.parse("latest"))
        assertEquals(true, UpdateCheck.isNewer("xtra5s-v1.66.0", "1.65.0"))
        assertEquals(false, UpdateCheck.isNewer("xtra5s-v1.65.0", "1.65.0")) // same version: no reinstall
        assertEquals(false, UpdateCheck.isNewer("xtra5s-v1.14.0", "1.64.0")) // old numbering is older
        assertEquals(true, UpdateCheck.isNewer("xtra5s-v1.66.1", "1.66.0"))
        assertEquals(true, UpdateCheck.isNewer("xtra5s-v2.0.0", "1.99.9"))
        assertNull(UpdateCheck.isNewer("latest", "1.66.0"))
    }

    // ---- Cheer highlight ----

    @Test
    fun bitsCounted() {
        val names = listOf("Cheer", "Kappa", "supertfCheer")
        assertEquals(200, SpecialMessages.bitsIn(200, "anything", names)) // Twitch's own count wins
        assertEquals(200, SpecialMessages.bitsIn(0, "Cheer100 Cheer100 my boss texted me", names)) // replay: add them up
        assertEquals(1500, SpecialMessages.bitsIn(null, "cheer1000 Kappa500", names))
        assertEquals(5000, SpecialMessages.bitsIn(null, "supertfCheer5000 hi", names))
        assertEquals(0, SpecialMessages.bitsIn(null, "room101 Cheer 100 2024", names)) // not cheers
        assertEquals(0, SpecialMessages.bitsIn(null, "Cheer100", emptyList()))
    }

    // ---- Crash log ----

    @Test
    fun crashLogNewestFirstAndTrimmed() {
        var log = CrashLog.withEntry(null, "one\nerror A")
        log = CrashLog.withEntry(log, "two\nerror B\n")
        assertEquals("${CrashLog.SEPARATOR}\ntwo\nerror B\n${CrashLog.SEPARATOR}\none\nerror A\n", log)
        for (i in 1..30) log = CrashLog.withEntry(log, "crash $i")
        assertEquals(CrashLog.MAX_ENTRIES, log.split("${CrashLog.SEPARATOR}\n").count { it.isNotBlank() })
        assertTrue(log.startsWith("${CrashLog.SEPARATOR}\ncrash 30\n"))
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
