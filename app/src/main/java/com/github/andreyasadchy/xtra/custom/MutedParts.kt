package com.github.andreyasadchy.xtra.custom

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.DefaultTimeBar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.ref.WeakReference

/**
 * Xtra5s custom: muted parts of VODs (Twitch's list of muted segments).
 *  - Shaded red bands on the seek bar (and the persistent progress bar) over each muted part.
 *  - While playing inside one, a button in the bottom-right of the video: "Muted · Skip 2m 10s";
 *    tap it (or press OK on a remote, unless you're on one of the controls) to jump to where the
 *    sound comes back. Settings > Player > Muted parts: show skip button (on by default).
 * Streamed VODs only (downloads don't have the list). Started from UpstreamUpdates; the video id
 * comes from PlayerFragment.xtra5PlayerState.
 */
object MutedParts {

    const val PREF_SKIP_BUTTON = "xtra5s_muted_skip_button"
    private const val TICK_MS = 500L
    private const val BAND_COLOR = 0xB3E53935.toInt()
    private const val TAG_BUTTON = "xtra5s_muted_skip"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, List<LongRange>>()
    private val loading = HashSet<String>()
    private var current: WeakReference<PlayerFragment>? = null

    // ---- The list (pure, tested) ----

    /**
     * Muted parts (milliseconds) from Twitch's reply: segments that touch or overlap (Twitch
     * splits them into 3-minute pieces) are joined into one part.
     */
    internal fun parse(body: String): List<LongRange> {
        val segments = ArrayList<Pair<Long, Long>>()
        fun walk(element: kotlinx.serialization.json.JsonElement?) {
            when (element) {
                is JsonObject -> {
                    val offset = (element["offset"] as? JsonPrimitive)?.longOrNull
                    val duration = (element["duration"] as? JsonPrimitive)?.longOrNull
                    if (offset != null && duration != null && duration > 0) segments.add(offset to offset + duration)
                    else element.values.forEach { walk(it) }
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk((json.parseToJsonElement(body) as? JsonObject)?.get("data"))
        return merge(segments)
    }

    /** Joins [segments] (seconds, start to end) that touch or overlap; returns milliseconds. */
    internal fun merge(segments: List<Pair<Long, Long>>): List<LongRange> {
        val out = ArrayList<LongRange>()
        var start = -1L
        var end = -1L
        segments.sortedBy { it.first }.forEach { (s, e) ->
            if (start < 0) { start = s; end = e }
            else if (s <= end + 2) end = maxOf(end, e)
            else { out.add(start * 1000 until end * 1000); start = s; end = e }
        }
        if (start >= 0) out.add(start * 1000 until end * 1000)
        return out
    }

    /** The muted part [positionMs] is in, if any. */
    internal fun partAt(parts: List<LongRange>, positionMs: Long): LongRange? = parts.firstOrNull { positionMs in it }

    // ---- Loading ----

    private fun fetch(context: Context, videoId: String): List<LongRange>? {
        val id = videoId.filter { it.isDigit() }.takeIf { it.isNotEmpty() } ?: return null
        val client = (context.applicationContext as XtraApp).xtraModule.okHttpClient.value
        val query = "query { video(id: \"$id\") { muteInfo { mutedSegmentConnection { nodes { duration offset } } } } }"
        val body = buildJsonObject { put("query", query) }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://gql.twitch.tv/gql").post(body).apply {
            TwitchApiHelper.getGQLHeaders(context).forEach { (k, v) -> header(k, v) }
        }.build()
        return client.newCall(request).execute().use { if (it.isSuccessful) parse(it.body.string()) else null }
    }

    private fun partsFor(player: PlayerFragment, videoId: String): List<LongRange>? {
        synchronized(cache) { cache[videoId]?.let { return it } }
        if (!loading.add(videoId)) return null
        val app = player.requireContext().applicationContext
        player.lifecycleScope.launch {
            val parts = try { withContext(Dispatchers.IO) { fetch(app, videoId) } } catch (_: Exception) { null }
            loading.remove(videoId)
            // A failed lookup counts as "none", so it isn't asked again every half second.
            synchronized(cache) { cache[videoId] = parts ?: emptyList() }
        }
        return null
    }

    // ---- On screen ----

    fun register(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f is PlayerFragment) attach(f, v)
            }
        }, true)
    }

    /** Bands over a seek bar's line, where DefaultTimeBar draws it. */
    private class Bands(private val bar: View) : Drawable() {
        var parts: List<LongRange> = emptyList()
        var duration = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAND_COLOR }
        override fun draw(canvas: Canvas) {
            if (parts.isEmpty() || duration <= 0) return
            val dp = bar.resources.displayMetrics.density
            val inset = 8 * dp // DefaultTimeBar's scrubber padding
            val left = bar.paddingLeft + inset
            val right = bar.width - bar.paddingRight - inset
            if (right <= left) return
            val centre = bar.height / 2f
            val half = 3 * dp
            parts.forEach { part ->
                val x1 = left + (right - left) * (part.first.coerceIn(0, duration) / duration.toFloat())
                val x2 = left + (right - left) * (part.last.coerceIn(0, duration) / duration.toFloat())
                if (x2 > x1) canvas.drawRect(x1, centre - half, maxOf(x2, x1 + 2 * dp), centre + half, paint)
            }
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    private fun bands(bar: DefaultTimeBar?): Bands? {
        bar ?: return null
        (bar.foreground as? Bands)?.let { return it }
        return Bands(bar).also { bar.foreground = it }
    }

    private fun attach(player: PlayerFragment, view: View) {
        val container = view.findViewById<FrameLayout>(R.id.playerLayout) ?: return
        val handler = Handler(Looper.getMainLooper())
        val tick = object : Runnable {
            override fun run() {
                if (!container.isAttachedToWindow || !player.isAdded) return
                update(player, container)
                handler.postDelayed(this, TICK_MS)
            }
        }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { handler.removeCallbacks(tick); handler.post(tick) }
            override fun onViewDetachedFromWindow(v: View) = handler.removeCallbacks(tick)
        })
        if (container.isAttachedToWindow) handler.post(tick)
    }

    private var shownPart: LongRange? = null

    private fun update(player: PlayerFragment, container: FrameLayout) {
        val view = player.view ?: return
        val state = player.xtra5PlayerState()
        val videoId = state.videoId
        val parts = if (state.type == BasePlaybackService.VIDEO && !videoId.isNullOrBlank()) partsFor(player, videoId).orEmpty() else emptyList()
        val duration = player.getTotalDuration()?.takeIf { it > 0 } ?: 0L
        listOf(view.findViewById<DefaultTimeBar>(R.id.progressBar), container.findViewById<DefaultTimeBar>(R.id.xtra5_progress_bar)).forEach { bar ->
            val bands = bands(bar) ?: return@forEach
            if (bands.parts != parts || bands.duration != duration) {
                bands.parts = parts
                bands.duration = duration
                bar?.invalidate()
            }
        }
        // The skip button, while inside a muted part (full-size video only).
        val position = player.getCurrentPosition() ?: 0L
        val part = if (state.isMaximized && container.context.prefs().getBoolean(PREF_SKIP_BUTTON, true)) partAt(parts, position) else null
        showButton(player, container, part, position)
    }

    private fun showButton(player: PlayerFragment, container: FrameLayout, part: LongRange?, position: Long) {
        val existing = container.findViewWithTag<TextView>(TAG_BUTTON)
        if (part == null) {
            existing?.visibility = View.GONE
            shownPart = null
            if (current?.get() === player) current = null
            return
        }
        val context = container.context
        val dp = context.resources.displayMetrics.density
        val button = existing ?: TextView(context).apply {
            tag = TAG_BUTTON
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val pad = (12 * dp).toInt()
            setPadding((16 * dp).toInt(), pad, (16 * dp).toInt(), pad)
            elevation = 8 * dp
            isClickable = true
            isFocusable = false // the remote's OK skips without moving focus here
            setOnClickListener { skip(player) }
            container.addView(this, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = (16 * dp).toInt()
                bottomMargin = (72 * dp).toInt() // above the controls bar
            })
        }
        // A gold outline on TVs / remotes: OK skips.
        val remote = !container.isInTouchMode
        button.background = GradientDrawable().apply {
            setColor(0xD9000000.toInt())
            cornerRadius = 22 * dp
            if (remote) setStroke((2 * dp).toInt(), SideBySide.GOLD) else setStroke((1 * dp).toInt(), 0x66FFFFFF)
        }
        val left = ((part.last + 1 - position) / 1000).coerceAtLeast(1)
        val text = "🔇 Muted · Skip " + HoldSeek.label(left) + "  ⏭"
        if (button.text.toString() != text) button.text = text
        button.visibility = View.VISIBLE
        button.bringToFront()
        shownPart = part
        current = WeakReference(player)
    }

    private fun skip(player: PlayerFragment) {
        val part = shownPart ?: return
        val duration = player.getTotalDuration() ?: Long.MAX_VALUE
        player.seek((part.last + 1).coerceAtMost(duration))
        shownPart = null
        player.view?.findViewById<FrameLayout>(R.id.playerLayout)?.findViewWithTag<View>(TAG_BUTTON)?.visibility = View.GONE
    }

    /**
     * Remote OK (from TvShortcuts): skips the muted part while the button shows, unless the
     * controls are open and one of them is selected. True when it skipped.
     */
    fun onOk(activity: Activity, playerFragment: Fragment?): Boolean {
        val player = playerFragment as? PlayerFragment ?: return false
        if (current?.get() !== player || shownPart == null) return false
        val view = player.view ?: return false
        val controls = view.findViewById<View>(R.id.playerControls)
        val focused = activity.currentFocus
        if (controls != null && controls.isShown && focused != null && generateSequence(focused) { it.parent as? View }.any { it === controls }) return false
        skip(player)
        return true
    }
}
