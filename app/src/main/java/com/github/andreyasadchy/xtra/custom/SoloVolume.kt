package com.github.andreyasadchy.xtra.custom

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.abs
import kotlin.math.pow

/**
 * Xtra5s custom: the normal (solo) player's volume.
 *  - Loudness curve: the volume number matches what you hear (30% sounds about 30% as loud,
 *    50% about half), the same as Side-By-Side. The saved setting (0-100) is unchanged; only the
 *    level sent to the player follows the curve ([gain]), and reading it back undoes it ([level]).
 *  - Hold-and-slide: with the video full size, touch and hold it (a short buzz), then slide left /
 *    right: half the video's width is 0 to 100%, with a volume bar in the middle while sliding.
 *    The same volume as the player's Volume button, saved when you let go.
 *    On VODs, holding the bottom strip of the video seeks instead (HoldSeek).
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: the volume lines in ExoPlayerService / MediaPlayerService / ExoPlayerFragment /
 * MediaPlayerFragment, and one line at the start of PlayerFragment's video touch listener.
 */
object SoloVolume {

    /** Slider level (0-1) to the level sent to the player. */
    fun gain(level: Float): Float = level.coerceIn(0f, 1f).toDouble().pow(5.0 / 3.0).toFloat()

    /**
     * Xtra5s (Dual Stream in split screen): a volume (0-1) for this stream instead of the saved
     * one, without changing the saved one. Cleared when you change the volume or the stream ends.
     */
    @Volatile var settingsOverride: Float? = null

    /** The saved volume setting (0-1) to the player's level, or this stream's own volume. */
    fun fromSettings(level: Float): Float = gain(settingsOverride ?: level)

    /** A volume you chose (Volume button, hold-and-slide): replaces this stream's own volume. */
    fun userChange(level: Float): Float {
        settingsOverride = null
        return gain(level)
    }

    /** The player's level back to the slider level (0-1). */
    fun level(gain: Float): Float = gain.coerceIn(0f, 1f).toDouble().pow(0.6).toFloat()

    // ---- Hold-and-slide ----

    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private var pending: Runnable? = null
    private var adjusting = false
    /** Xtra5s: this hold is the VOD seek (bottom strip of the video, see HoldSeek), not the volume. */
    private var seeking = false
    private var passing = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startVolume = 100
    private var volume = 100

    /**
     * Called first for every touch on the video. Returns true when the touch is the volume slide
     * (the player's own handling then sees nothing more of it).
     */
    fun onTouch(player: PlayerFragment, view: View, event: MotionEvent): Boolean {
        if (passing) return false
        if (DoubleTapSeek.onTouch(player, view, event)) return true // Xtra5s: YouTube-style double-tap seek on VODs
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                ChatInputFocus.release(view.context) // Xtra5s: tapping the video stops the input cursor
                cancelPending()
                adjusting = false
                if (!player.xtra5PlayerState().isMaximized || overButton(player, event)) return false
                downX = event.rawX
                downY = event.rawY
                val down = MotionEvent.obtain(event)
                pending = Runnable {
                    pending = null
                    start(player, view, down)
                    down.recycle()
                }.also { handler.postDelayed(it, ViewConfiguration.getLongPressTimeout().toLong()) }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (adjusting && seeking) {
                    HoldSeek.move(player, event.rawX)
                    return true
                }
                if (adjusting) {
                    val layout = videoLayout(player) ?: view
                    val change = ((event.rawX - startX) / (layout.width / 2f).coerceAtLeast(1f) * 100).toInt()
                    val newVolume = (startVolume + change).coerceIn(0, 100)
                    if (newVolume != volume) {
                        volume = newVolume
                        player.changeVolume(volume / 100f)
                    }
                    showBar(player, volume)
                    return true
                }
                // Moving before the buzz: a drag (minimize, seek...), not a hold.
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (pending != null && (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop)) cancelPending()
                return false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                if (adjusting && seeking) {
                    adjusting = false
                    seeking = false
                    HoldSeek.end(player)
                    return true
                }
                if (adjusting) {
                    adjusting = false
                    view.context.prefs().edit { putInt(C.PLAYER_VOLUME, volume) }
                    hideBar(player)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                if (!adjusting) cancelPending()
                return adjusting
            }
        }
        return adjusting
    }

    private fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    private fun start(player: PlayerFragment, view: View, down: MotionEvent) {
        if (!player.isAdded || player.view == null || !player.xtra5PlayerState().isMaximized) return
        // End the player's own handling of this touch (no tap, double-tap or drag to minimize).
        passing = true
        try {
            MotionEvent.obtain(down).apply { action = MotionEvent.ACTION_CANCEL }.let { cancel ->
                view.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        } finally {
            passing = false
        }
        view.isPressed = false
        adjusting = true
        seeking = HoldSeek.applies(player, downY)
        if (seeking) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            HoldSeek.begin(player, downX)
            return
        }
        startX = downX
        startVolume = view.context.prefs().getInt(C.PLAYER_VOLUME, 100).coerceIn(0, 100)
        volume = startVolume
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showBar(player, volume)
    }

    /** True when the finger is on one of the player's buttons or the seek bar (they keep their own holds). */
    internal fun overButton(player: PlayerFragment, event: MotionEvent): Boolean {
        val root = player.view ?: return false
        val controls = root.findViewById<View>(R.id.playerControls) ?: return false
        if (!controls.isShown) return false
        fun hit(view: View): Boolean {
            if (!view.isShown) return false
            val loc = IntArray(2).also { view.getLocationOnScreen(it) }
            val inside = event.rawX >= loc[0] && event.rawX < loc[0] + view.width && event.rawY >= loc[1] && event.rawY < loc[1] + view.height
            if (!inside) return false
            if (view !== controls && (view.isClickable || view.isLongClickable)) return true
            if (view is ViewGroup) for (i in 0 until view.childCount) if (hit(view.getChildAt(i))) return true
            return false
        }
        return hit(controls)
    }

    private fun videoLayout(player: PlayerFragment): FrameLayout? = player.view?.findViewById(R.id.playerLayout)

    // ---- Volume bar (middle of the video) ----

    private const val TRACK_DP = 160

    private fun showBar(player: PlayerFragment, volume: Int) {
        val layout = videoLayout(player) ?: return
        val dp = layout.resources.displayMetrics.density
        val bar = layout.findViewWithTag<LinearLayout>("xtra5s_volume_bar") ?: LinearLayout(layout.context).apply {
            tag = "xtra5s_volume_bar"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, (14 * dp).toInt(), pad)
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = 24 * dp }
            elevation = 8 * dp
            addView(ImageView(context).apply { imageTintList = SideBySide.WHITE }, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
            val track = FrameLayout(context).apply { background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = 3 * dp } }
            track.addView(View(context).apply { background = GradientDrawable().apply { setColor(SideBySide.GOLD); cornerRadius = 3 * dp } }, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(track, LinearLayout.LayoutParams((TRACK_DP * dp).toInt(), (6 * dp).toInt()).apply {
                marginStart = (10 * dp).toInt()
                marginEnd = (10 * dp).toInt()
            })
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minEms = 2
                gravity = Gravity.END
            })
            layout.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        handler.removeCallbacksAndMessages(bar)
        bar.animate().cancel()
        (bar.getChildAt(0) as ImageView).setImageResource(if (volume == 0) R.drawable.xtra5s_volume_off else R.drawable.xtra5s_volume_up)
        val fill = (bar.getChildAt(1) as FrameLayout).getChildAt(0)
        fill.layoutParams = fill.layoutParams.apply { width = (TRACK_DP * dp).toInt() * volume / 100 }
        (bar.getChildAt(2) as TextView).text = "$volume%"
        bar.alpha = 1f
        bar.isVisible = true
        bar.bringToFront()
    }

    private fun hideBar(player: PlayerFragment) {
        val bar = videoLayout(player)?.findViewWithTag<LinearLayout>("xtra5s_volume_bar") ?: return
        handler.removeCallbacksAndMessages(bar)
        handler.postAtTime({
            bar.animate().alpha(0f).setDuration(250).withEndAction { bar.isVisible = false }.start()
        }, bar, android.os.SystemClock.uptimeMillis() + 1000)
    }
}
