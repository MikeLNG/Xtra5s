package com.github.andreyasadchy.xtra.custom

import android.app.UiModeManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Xtra5s custom: Dual Stream in split screen, with Xtra5s Companion.
 *
 * Xtra5s Companion is the same app built under another package (`-Pxtra5sCompanion`), so it
 * installs next to Xtra5s. On devices with split screen (not TVs), Dual Stream's Watch plays
 * Stream 1 in Xtra5s's normal player and opens Stream 2 in Xtra5s Companion's normal player in
 * the other half of the screen (Android's "launch adjacent"). Two busy chats in one window made
 * some devices show stale frames; one chat per window doesn't.
 *  - Each side opens at the quality and volume chosen in the "+" popup, once, without changing
 *    that app's saved quality or volume (AudioOnlyOnce, SoloVolume).
 *  - Each time, Xtra5s Companion first copies Xtra5s's settings and login (SyncProvider: only
 *    apps signed with the same key may ask).
 *  - Back in either player closes that stream and its app's half of the screen.
 * TVs, devices without split screen, and Xtra5s without Companion installed keep the one-screen
 * Dual Stream (SideBySideActivity). A switch on the Dual Stream tab turns split screen off.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: MainActivity.handleIntent (start), PlayerFragment's Back callback and
 * minimize(), the updater's APK choice (MainViewModel.checkUpdates), the manifest (provider,
 * permission, package queries).
 */
object CompanionApp {

    const val MAIN_PACKAGE = "com.prime385.xtra5s"
    const val COMPANION_PACKAGE = "com.prime385.xtra5s.companion"
    const val NAME = "Xtra5s Companion"
    private const val AUTHORITY = "$MAIN_PACKAGE.xtra5ssync"
    private const val PREF_SPLIT = "xtra5s_dual_split_screen"

    private const val EXTRA_SESSION = "xtra5s_companion_session"
    private const val EXTRA_QUALITY = "xtra5s_companion_quality"
    private const val EXTRA_VOLUME = "xtra5s_companion_volume"
    private const val EXTRA_STREAM = "xtra5s_companion_stream"

    /** Settings that belong to each app itself and aren't copied (updates). */
    private fun ownKey(key: String) = key.contains("update", true)

    /** A stream opened by Dual Stream in split screen is playing (Back closes this half). */
    @Volatile private var session = false

    fun isCompanion(context: Context) = context.packageName == COMPANION_PACKAGE

    fun isTv(context: Context): Boolean =
        (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun companionInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(COMPANION_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** Whether the Dual Stream tab offers the split-screen switch (Xtra5s, not on TV). */
    fun canOffer(context: Context) = !isCompanion(context) && !isTv(context)

    fun splitEnabled(context: Context) = context.prefs().getBoolean(PREF_SPLIT, true)
    fun setSplitEnabled(context: Context, enabled: Boolean) = context.prefs().edit { putBoolean(PREF_SPLIT, enabled) }

    /** Watch opens split screen with Companion (instead of the one-screen Dual Stream). */
    fun useSplit(context: Context) = canOffer(context) && splitEnabled(context) && companionInstalled(context)

    // ---- Starting a pair (Xtra5s) ----

    /**
     * Plays [first] here and [second] in Companion, side by side. Returns false (nothing started)
     * if a channel isn't live or wasn't found, so the one-screen Dual Stream (which waits for
     * offline channels) is used instead.
     */
    suspend fun start(activity: MainActivity, first: SideBySide.Side, second: SideBySide.Side): Boolean {
        val streams = withContext(Dispatchers.IO) {
            try {
                val module = (activity.applicationContext as XtraApp).xtraModule
                val networkLibrary = activity.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
                module.graphQLRepository.loadQueryUsersStream(networkLibrary, TwitchApiHelper.getGQLHeaders(activity), logins = listOf(first.login, second.login))
                    .data?.users?.mapNotNull { user ->
                        val stream = user?.stream ?: return@mapNotNull null
                        (user.login?.lowercase() ?: return@mapNotNull null) to Stream(
                            id = stream.id,
                            channelId = user.id,
                            channelLogin = user.login,
                            channelName = user.displayName,
                            channelImageURL = user.profileImageURL,
                            gameId = stream.game?.id,
                            gameSlug = stream.game?.slug,
                            gameName = stream.game?.displayName,
                            title = stream.broadcaster?.broadcastSettings?.title,
                            thumbnailURL = stream.previewImageURL,
                            createdAt = stream.createdAt?.toString(),
                            viewerCount = stream.viewersCount,
                        )
                    }?.toMap()
            } catch (_: Exception) {
                null
            }
        } ?: return false
        val one = streams[first.login] ?: return false
        val two = streams[second.login] ?: return false
        if (activity.isFinishing || activity.isDestroyed) return true
        // Stream 1: this app's normal player, at the popup's quality and volume (once).
        prepare(first.quality, if (first.muted) 0 else first.volume)
        activity.startStream(one)
        session = true
        // Stream 2: Companion, next to this app.
        try {
            activity.startActivity(Intent(MainActivity.INTENT_LIVE_NOTIFICATION).apply {
                setClassName(COMPANION_PACKAGE, MainActivity::class.java.name)
                // Plain values, not the Stream object: the two apps are built separately and
                // needn't agree on its packed form. Companion rebuilds it (onIntent).
                putExtra(EXTRA_STREAM, Bundle().apply {
                    putString("id", two.id); putString("channelId", two.channelId); putString("channelLogin", two.channelLogin)
                    putString("channelName", two.channelName); putString("channelImageURL", two.channelImageURL)
                    putString("gameId", two.gameId); putString("gameSlug", two.gameSlug); putString("gameName", two.gameName)
                    putString("title", two.title); putString("thumbnailURL", two.thumbnailURL); putString("createdAt", two.createdAt)
                    two.viewerCount?.let { putInt("viewerCount", it) }
                })
                putExtra(EXTRA_SESSION, true)
                putExtra(EXTRA_QUALITY, second.quality)
                putExtra(EXTRA_VOLUME, if (second.muted) 0 else second.volume)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
            })
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't open $NAME", Toast.LENGTH_LONG).show()
        }
        return true
    }

    private fun prepare(quality: String, volume: Int) {
        AudioOnlyOnce.requestQuality(quality)
        SoloVolume.settingsOverride = volume.coerceIn(0, 100) / 100f
    }

    // ---- Opened by Xtra5s (Companion) ----

    /** Start of MainActivity.handleIntent: a Dual Stream stream from Xtra5s, set up before it plays. */
    fun onIntent(activity: MainActivity, intent: Intent?) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SESSION, false) || !isCompanion(activity)) return
        intent.removeExtra(EXTRA_SESSION) // once (not again on a restore)
        intent.getBundleExtra(EXTRA_STREAM)?.let { b ->
            intent.putExtra(MainActivity.KEY_VIDEO, Stream(
                id = b.getString("id"), channelId = b.getString("channelId"), channelLogin = b.getString("channelLogin"),
                channelName = b.getString("channelName"), channelImageURL = b.getString("channelImageURL"),
                gameId = b.getString("gameId"), gameSlug = b.getString("gameSlug"), gameName = b.getString("gameName"),
                title = b.getString("title"), thumbnailURL = b.getString("thumbnailURL"), createdAt = b.getString("createdAt"),
                viewerCount = if (b.containsKey("viewerCount")) b.getInt("viewerCount") else null,
            ))
        }
        syncFromMain(activity)
        prepare(intent.getStringExtra(EXTRA_QUALITY) ?: SideBySide.DEFAULT_QUALITY, intent.getIntExtra(EXTRA_VOLUME, 100))
        session = true
    }

    /** Copies Xtra5s's settings and login into Companion. */
    private fun syncFromMain(context: Context) {
        val result = try {
            context.contentResolver.call(Uri.parse("content://$AUTHORITY"), "settings", null, null)
        } catch (_: Exception) {
            null
        } ?: return
        result.getBundle("prefs")?.let { apply(context.prefs(), it) }
        result.getBundle("tokens")?.let { apply(context.tokenPrefs(), it) }
    }

    private fun apply(prefs: SharedPreferences, values: Bundle) {
        prefs.edit(commit = true) {
            values.keySet().filterNot { ownKey(it) }.forEach { key ->
                @Suppress("DEPRECATION")
                when (val value = values.get(key)) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is ArrayList<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    null -> remove(key)
                }
            }
        }
    }

    // ---- Back / end of the stream ----

    /** PlayerFragment's Back: for a split-screen Dual Stream stream, close it and this app's half. */
    fun onPlayerBack(player: PlayerFragment): Boolean {
        if (!session) return false
        val activity = player.activity as? MainActivity ?: return false
        endSession()
        try {
            player.close()
            activity.closePlayer()
        } catch (_: Exception) {}
        activity.finishAndRemoveTask()
        return true
    }

    /** The player was minimized or closed: it's an ordinary stream from now on. */
    fun endSession() {
        session = false
        SoloVolume.settingsOverride = null
    }

    // ---- Updates ----

    /** The updater takes this app's own APK from a release (both apps' APKs are in each). */
    fun isMyUpdate(context: Context, assetName: String?): Boolean = isMyUpdate(isCompanion(context), assetName)

    internal fun isMyUpdate(companion: Boolean, assetName: String?): Boolean {
        val name = assetName ?: return true
        val companionApk = name.contains("Companion", true)
        return if (companion) companionApk else !companionApk
    }

    // ---- Settings for Companion (Xtra5s side) ----

    /** Gives Companion this app's settings and login; only apps signed with the same key may ask. */
    class SyncProvider : ContentProvider() {
        override fun onCreate() = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            val context = context ?: return null
            if (method != "settings" || isCompanion(context)) return null
            return Bundle().apply {
                putBundle("prefs", bundleOf(context.prefs()))
                putBundle("tokens", bundleOf(context.tokenPrefs()))
            }
        }

        private fun bundleOf(prefs: SharedPreferences) = Bundle().apply {
            prefs.all.forEach { (key, value) ->
                if (ownKey(key)) return@forEach
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringArrayList(key, ArrayList(value.filterIsInstance<String>()))
                }
            }
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
