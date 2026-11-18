package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.widget.TextView
import coil3.asDrawable
import coil3.imageLoader
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.github.andreyasadchy.xtra.BuildConfig
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.chat.Emote
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import com.github.andreyasadchy.xtra.util.tokenPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Xtra5s custom: emotes in the chat commands table (BotCommands). A reply's words that are
 * emote names show as the emote, as in chat:
 *  - the channel's Twitch emotes (sub emotes, whether or not you're subscribed), Twitch global
 *    emotes, and (logged in) every Twitch emote you can use;
 *  - 7TV, BTTV and FFZ, the channel's and the global ones, with the chat's loaders and settings.
 * Twitch emotes win over the others, then channel before global (7TV, BTTV, FFZ), as in chat.
 * The table shows text first; emotes swap in as they load. Kept 10 minutes per channel.
 */
object BotEmotes {

    private const val CACHE_MS = 10 * 60_000L
    private val cache = HashMap<String, Pair<Long, Map<String, Emote>>>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- Matching (pure, tested) ----

    /** Emote names in [text]: whole words (split by spaces), exact case, as chat matches them. */
    internal fun find(text: String, emotes: Map<String, *>): List<IntRange> {
        if (emotes.isEmpty()) return emptyList()
        val found = ArrayList<IntRange>()
        var start = -1
        for (i in 0..text.length) {
            val space = i == text.length || text[i].isWhitespace()
            if (space) {
                if (start >= 0 && emotes.containsKey(text.substring(start, i))) found.add(start until i)
                start = -1
            } else if (start < 0) start = i
        }
        return found
    }

    /** First list wins for a name (Twitch, then channel 7TV / BTTV / FFZ, then global ones). */
    internal fun <T> byName(lists: List<List<T>>, name: (T) -> String?): Map<String, T> {
        val out = LinkedHashMap<String, T>()
        lists.forEach { list -> list.forEach { item -> name(item)?.takeIf { it.isNotEmpty() }?.let { if (it !in out) out[it] = item } } }
        return out
    }

    /** Twitch emotes from a GraphQL reply: every {"id", "token"} pair under the given path. */
    internal fun parseTwitchEmotes(body: String): List<Emote> {
        val out = ArrayList<Emote>()
        fun walk(element: kotlinx.serialization.json.JsonElement?) {
            when (element) {
                is JsonObject -> {
                    val id = (element["id"] as? JsonPrimitive)?.contentOrNull
                    val token = (element["token"] as? JsonPrimitive)?.contentOrNull
                    if (id != null && token != null) out.add(twitchEmote(id, token))
                    else element.values.forEach { walk(it) }
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk((json.parseToJsonElement(body) as? JsonObject)?.get("data"))
        return out
    }

    private fun twitchEmote(id: String, name: String) = Emote(
        name = name,
        url1x = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/1.0",
        url2x = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0",
        url3x = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/2.0",
        url4x = "https://static-cdn.jtvnw.net/emoticons/v2/$id/default/dark/3.0",
        format = "gif",
    )

    // ---- Loading ----

    private fun gql(context: Context, query: String): String? {
        val client = (context.applicationContext as XtraApp).xtraModule.okHttpClient.value
        val body = buildJsonObject { put("query", query) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://gql.twitch.tv/gql").post(body).apply {
            TwitchApiHelper.getGQLHeaders(context).forEach { (k, v) -> header(k, v) }
        }.build()
        return client.newCall(request).execute().use { if (it.isSuccessful) it.body.string() else null }
    }

    private fun safeLogin(login: String) = login.lowercase().filter { it.isLetterOrDigit() || it == '_' }

    suspend fun load(context: Context, login: String): Map<String, Emote> {
        val key = login.lowercase()
        synchronized(cache) { cache[key]?.let { (time, map) -> if (System.currentTimeMillis() - time < CACHE_MS) return map } }
        val app = context.applicationContext
        val module = (app as XtraApp).xtraModule
        val prefs = app.prefs()
        val networkLibrary = prefs.getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val useWebp = prefs.getBoolean(C.CHAT_USE_WEBP, true)
        val name = safeLogin(login)
        val map = withContext(Dispatchers.IO) {
            suspend fun <T> safe(block: suspend () -> List<T>?): List<T> = try { block().orEmpty() } catch (_: Exception) { emptyList() }
            val channelId = try {
                module.graphQLRepository.loadQueryUsersStream(networkLibrary, TwitchApiHelper.getGQLHeaders(app), logins = listOf(name)).data?.users?.firstOrNull()?.id
            } catch (_: Exception) { null }
            coroutineScope {
                val repo = module.playerRepository
                val channelTwitch = async { safe { parseTwitchEmotes(gql(app, "query { user(login: \"$name\") { subscriptionProducts { emotes { id token } } } }") ?: return@safe null) } }
                val globalTwitch = async { safe { parseTwitchEmotes(gql(app, "query { emoteSet(id: \"0\") { emotes { id token } } }") ?: return@safe null) } }
                val userTwitch = async {
                    safe {
                        val helix = TwitchApiHelper.getHelixHeaders(app)
                        val gqlHeaders = TwitchApiHelper.getGQLHeaders(app, true)
                        if (gqlHeaders[C.HEADER_TOKEN].isNullOrBlank() && helix[C.HEADER_TOKEN].isNullOrBlank()) return@safe null
                        repo.loadUserEmotes(networkLibrary, helix, gqlHeaders, channelId, app.tokenPrefs().getString(C.USER_ID, null),
                            prefs.getBoolean(C.ANIMATED_EMOTES, true), prefs.getBoolean(C.ENABLE_INTEGRITY, false))
                            .map { Emote(name = it.name, url1x = it.url1x, url2x = it.url2x, url3x = it.url3x, url4x = it.url4x, format = it.format) }
                    }
                }
                val channelStv = async {
                    safe {
                        val id = channelId ?: return@safe null
                        val user = repo.loadSTVUser(repo.loadSTVUserResponse(networkLibrary, id), useWebp)
                        user.second?.takeIf { it.isNotEmpty() }
                            ?: user.first?.takeIf { it.isNotBlank() }?.let { repo.loadSTVEmoteSet(repo.loadSTVEmoteSetResponse(networkLibrary, it), useWebp, false).second }
                    }
                }
                val channelBttv = async { safe { channelId?.let { repo.loadBTTVEmotes(repo.loadBTTVEmotesResponse(networkLibrary, it), useWebp) } } }
                val channelFfz = async { safe { channelId?.let { repo.loadFFZEmotes(repo.loadFFZEmotesResponse(networkLibrary, it), useWebp) } } }
                val globalStv = async { safe { repo.loadSTVEmoteSet(repo.loadGlobalSTVEmoteSetResponse(networkLibrary), useWebp, true).second } }
                val globalBttv = async { safe { repo.loadGlobalBTTVEmotes(repo.loadGlobalBTTVEmotesResponse(networkLibrary), useWebp) } }
                val globalFfz = async { safe { repo.loadGlobalFFZEmotes(repo.loadGlobalFFZEmotesResponse(networkLibrary), useWebp) } }
                byName(listOf(
                    channelTwitch.await(), userTwitch.await(), globalTwitch.await(),
                    channelStv.await(), channelBttv.await(), channelFfz.await(),
                    globalStv.await(), globalBttv.await(), globalFfz.await(),
                )) { it.name }
            }
        }
        synchronized(cache) { cache[key] = System.currentTimeMillis() to map }
        return map
    }

    // ---- Showing ----

    private fun url(emote: Emote, quality: String) = when (quality) {
        "1" -> emote.url1x ?: emote.url2x ?: emote.url3x ?: emote.url4x
        "2" -> emote.url2x ?: emote.url1x ?: emote.url3x ?: emote.url4x
        "3" -> emote.url3x ?: emote.url2x ?: emote.url4x ?: emote.url1x
        else -> emote.url4x ?: emote.url3x ?: emote.url2x ?: emote.url1x
    }

    /**
     * Swaps the emote names in [view]'s text (a [SpannableString] of [text]) for their images,
     * as each one loads. Uses the chat's emote size, quality and animation settings.
     */
    fun apply(view: TextView, text: SpannableString, emotes: Map<String, Emote>) {
        val ranges = find(text.toString(), emotes)
        if (ranges.isEmpty()) return
        val context = view.context
        val prefs = context.prefs()
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, prefs.getString(C.CHAT_EMOTE_SIZE, "29.5")?.toFloatOrNull() ?: 29.5f, context.resources.displayMetrics).toInt()
        val quality = prefs.getString(C.CHAT_IMAGE_QUALITY, "4") ?: "4"
        val animate = prefs.getBoolean(C.ANIMATED_EMOTES, true)
        val token = Any()
        view.setTag(com.github.andreyasadchy.xtra.R.id.xtra5s_player_commands, token)
        ranges.forEach { range ->
            val emote = emotes[text.substring(range.first, range.last + 1)] ?: return@forEach
            val address = url(emote, quality) ?: return@forEach
            context.imageLoader.enqueue(ImageRequest.Builder(context).apply {
                data(address)
                httpHeaders(NetworkHeaders.Builder().apply { add("User-Agent", "Xtra/" + BuildConfig.VERSION_NAME) }.build())
                target(onSuccess = { image ->
                    // The row was reused for another command meanwhile: leave it.
                    if (view.getTag(com.github.andreyasadchy.xtra.R.id.xtra5s_player_commands) !== token) return@target
                    val drawable = image.asDrawable(context.resources)
                    val width = if (drawable.intrinsicHeight > 0) size * drawable.intrinsicWidth / drawable.intrinsicHeight else size
                    drawable.setBounds(0, 0, width, size)
                    if (drawable is Animatable && animate) {
                        drawable.callback = object : Drawable.Callback {
                            override fun invalidateDrawable(who: Drawable) = view.invalidate()
                            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) { view.postDelayed(what, `when` - android.os.SystemClock.uptimeMillis()) }
                            override fun unscheduleDrawable(who: Drawable, what: Runnable) { view.removeCallbacks(what) }
                        }
                        drawable.start()
                    }
                    text.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    view.text = text
                })
            }.build())
        }
    }
}
