package com.github.andreyasadchy.xtra.custom

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.media3.ui.DefaultTimeBar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import kotlin.math.abs
import kotlin.math.pow

/**
 * Xtra5s custom: hold-and-drag seek on VODs. Touch and hold the bottom strip of the video
 * (a short buzz), then drag: left rewinds, right fast-forwards, in steps from 2 seconds up to
 * hours, each step the same short drag (3% of the screen's width) so the jumps grow faster the
 * further you slide, and Start / End in the last ~200 pixels of the screen (a tick at each step). The video keeps playing meanwhile; a
 * two-sided pill (like the volume slide's) shows the jump and where it lands, and the seek bar's
 * marker waits there in gold. Letting go jumps there;
 * dragging back to where you started first cancels. Elsewhere on the video, holding is the
 * volume slide ([SoloVolume], which starts this one).
 */
object HoldSeek {

    /** The jumps, in seconds: fine at first, then bigger and bigger (Start / End at the edge). */
    internal val STEPS = longArrayOf(
        2, 5, 10, 20, 30, 45, 60, 90, 120, 180, 300, 420, 600, 900, 1200,
        1800, 2700, 3600, 5400, 7200, 10800, 14400, 18000, 21600, 28800, 36000, 43200, 57600, 86400, 172800,
    )

    /** Bottom part of the video that seeks instead of changing the volume. */
    internal const val BAND = 0.25f

    /** Drags shorter than this (dp) don't seek: a cancel. */
    internal const val DEAD_ZONE_DP = 16

    /** The last pixels of the screen, on the side you drag to: Start / End. */
    internal const val EDGE_PX = 200f

    /** Once at Start / End, how far back out (pixels) the finger must go to leave it. */
    internal const val EDGE_RELEASE_PX = 60f

    /** Each step takes this part of the screen's width of drag. */
    internal const val STEP_FRACTION = 0.03f

    /**
     * The jump in seconds (negative = back) for a drag of [dx] pixels, each step taking [step]
     * pixels after [dead] pixels of cancel zone, when [backSeconds] / [forwardSeconds] are left
     * before the start / end. The steps are evenly spaced and grow quickly (2s, 5s, 10s ... 20m,
     * 30m, 1h ...); never past the biggest step that fits. [atEdge]: Start / End.
     */
    internal fun jumpFor(dx: Float, step: Float, dead: Float, atEdge: Boolean, backSeconds: Long, forwardSeconds: Long): Long {
        val distance = abs(dx)
        if (distance < dead) return 0
        val left = if (dx < 0) backSeconds else forwardSeconds
        if (left <= 0) return 0
        val jump = if (atEdge) left else {
            val fits = STEPS.count { it < left }
            if (fits == 0) left
            else STEPS[((distance - dead) / step.coerceAtLeast(1f)).toInt().coerceIn(0, fits - 1)]
        }
        return if (dx < 0) -jump else jump
    }

    /**
     * Whether Start / End is showing after the finger moved to [rawX] (screen [width]) on a drag
     * of [dx] that started at [startX], given whether it [was] showing: on when the finger is in
     * the last [EDGE_PX] of the screen on the side it's dragging to (not if the drag started
     * there), off again only once it's [EDGE_RELEASE_PX] back out, so it doesn't flicker.
     */
    internal fun edgeShowing(rawX: Float, startX: Float, dx: Float, width: Float, was: Boolean): Boolean {
        if (dx == 0f) return false
        val toEdge = if (dx < 0) rawX else width - rawX
        val startToEdge = if (dx < 0) startX else width - startX
        if (startToEdge <= EDGE_PX) return false
        return if (was) toEdge <= EDGE_PX + EDGE_RELEASE_PX else toEdge <= EDGE_PX
    }

    /** How much of its side the pill's gold fill covers (0-1), for [jump] out of [left] seconds. */
    internal fun fill(jump: Long, left: Long): Float {
        val size = abs(jump)
        if (size == 0L) return 0f
        if (size >= left) return 1f
        val index = STEPS.indexOfFirst { it == size }.takeIf { it >= 0 } ?: return 0f
        val fits = STEPS.count { it < left }
        return (0.9f * (index + 1) / fits).coerceIn(0.04f, 0.9f)
    }

    /** "2s", "1m 30s", "20m", "1h 30m". */
    internal fun label(seconds: Long): String {
        val s = abs(seconds)
        val h = s / 3600
        val m = s % 3600 / 60
        val rest = s % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m == 0L -> "${rest}s"
            rest == 0L -> "${m}m"
            else -> "${m}m ${rest}s"
        }
    }

    /** True for VODs and saved videos when [rawY] is in the bottom strip of the video. */
    fun applies(player: PlayerFragment, rawY: Float): Boolean {
        val type = player.xtra5PlayerState().type
        if (type != BasePlaybackService.VIDEO && type != BasePlaybackService.OFFLINE_VIDEO) return false
        if ((player.getTotalDuration() ?: 0L) <= 0L) return false
        val layout = videoLayout(player) ?: return false
        val loc = IntArray(2).also { layout.getLocationOnScreen(it) }
        val bottom = loc[1] + layout.height
        return rawY < bottom && rawY >= bottom - layout.height * BAND
    }

    private var startX = 0f
    private var startPosition = 0L
    private var duration = 0L
    private var jump = 0L
    private var edge = false
    private var edgeBack = false

    fun begin(player: PlayerFragment, rawX: Float) {
        startX = rawX
        duration = player.getTotalDuration() ?: 0L
        startPosition = (player.getCurrentPosition() ?: 0L).coerceIn(0L, duration)
        jump = 0L
        edge = false
        player.xtra5PlayerState().showController()
        // A pressed seek bar keeps its marker where it's put and the controls from hiding.
        player.view?.findViewById<DefaultTimeBar>(R.id.progressBar)?.apply {
            isPressed = true
            setScrubberColor(SideBySide.GOLD)
        }
        show(player)
    }

    fun move(player: PlayerFragment, rawX: Float) {
        val layout = videoLayout(player) ?: return
        val dx = rawX - startX
        val screen = layout.rootView.width.takeIf { it > 0 } ?: layout.resources.displayMetrics.widthPixels
        // Leaving Start / End by dragging back the other way past the start point ends it too.
        if (dx < 0 != edgeBack) edge = false
        edge = edgeShowing(rawX, startX, dx, screen.toFloat(), edge)
        edgeBack = dx < 0
        val dead = DEAD_ZONE_DP * layout.resources.displayMetrics.density
        val newJump = jumpFor(dx, screen * STEP_FRACTION, dead, edge, startPosition / 1000, (duration - startPosition) / 1000)
        if (newJump != jump) {
            jump = newJump
            layout.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        show(player)
    }

    fun end(player: PlayerFragment) {
        val view = player.view
        val bar = view?.findViewById<DefaultTimeBar>(R.id.progressBar)
        bar?.isPressed = false
        bar?.setScrubberColor(DefaultTimeBar.DEFAULT_SCRUBBER_COLOR)
        if (jump != 0L) {
            player.seek(target())
        } else {
            val position = player.getCurrentPosition() ?: startPosition
            bar?.setPosition(position)
            view?.findViewById<TextView>(R.id.position)?.text = DateUtils.formatElapsedTime(position / 1000)
        }
        jump = 0L
        player.xtra5PlayerState().showController() // restarts the normal auto-hide
        hide(player)
    }

    private fun target(): Long = when {
        jump == 0L -> startPosition
        jump < 0 && -jump >= startPosition / 1000 -> 0L // Start
        jump > 0 && jump >= (duration - startPosition) / 1000 -> duration // End
        else -> (startPosition + jump * 1000L).coerceIn(0L, duration)
    }

    private fun videoLayout(player: PlayerFragment): FrameLayout? = player.view?.findViewById(R.id.playerLayout)

    // ---- The pill (middle of the video, like the volume slide's) and the held marker ----

    private const val TAG = "xtra5s_seek_pill"
    private const val TRACK_DP = 180

    private fun show(player: PlayerFragment) {
        val layout = videoLayout(player) ?: return
        val dp = layout.resources.displayMetrics.density
        val trackWidth = (TRACK_DP * dp).toInt()
        val pill = layout.findViewWithTag<android.widget.LinearLayout>(TAG) ?: android.widget.LinearLayout(layout.context).apply {
            tag = TAG
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, (16 * dp).toInt(), pad)
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = 24 * dp }
            elevation = 8 * dp
            addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.xtra5s_fast_rewind) },
                android.widget.LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
            val track = FrameLayout(context)
            // The line, a mark in the middle (no jump), and the gold fill growing out from it.
            track.addView(View(context).apply { background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = 3 * dp } },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (6 * dp).toInt(), Gravity.CENTER_VERTICAL))
            track.addView(View(context).apply { background = GradientDrawable().apply { setColor(SideBySide.GOLD); cornerRadius = 3 * dp } },
                FrameLayout.LayoutParams(0, (6 * dp).toInt(), Gravity.CENTER_VERTICAL or Gravity.START))
            track.addView(View(context).apply { setBackgroundColor(Color.WHITE) },
                FrameLayout.LayoutParams((2 * dp).toInt(), (14 * dp).toInt(), Gravity.CENTER))
            addView(track, android.widget.LinearLayout.LayoutParams(trackWidth, (14 * dp).toInt()).apply {
                marginStart = (8 * dp).toInt()
                marginEnd = (8 * dp).toInt()
            })
            addView(android.widget.ImageView(context).apply { setImageResource(R.drawable.xtra5s_fast_forward) },
                android.widget.LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
            addView(TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                minEms = 6
                gravity = Gravity.END
            }, android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = (12 * dp).toInt()
            })
            layout.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val to = target()
        val back = jump < 0
        val left = if (back) startPosition / 1000 else (duration - startPosition) / 1000
        val half = trackWidth / 2
        val width = (half * fill(jump, left)).toInt()
        val gold = android.content.res.ColorStateList.valueOf(SideBySide.GOLD)
        (pill.getChildAt(0) as android.widget.ImageView).imageTintList = if (jump < 0) gold else SideBySide.WHITE
        (pill.getChildAt(2) as android.widget.ImageView).imageTintList = if (jump > 0) gold else SideBySide.WHITE
        val fillView = (pill.getChildAt(1) as FrameLayout).getChildAt(1)
        fillView.layoutParams = (fillView.layoutParams as FrameLayout.LayoutParams).apply {
            this.width = width
            leftMargin = if (back) half - width else half
        }
        (pill.getChildAt(3) as TextView).text = when {
            jump == 0L -> "Drag to seek"
            to == 0L && back -> "Start"
            to == duration && !back -> "End"
            else -> (if (back) "−" else "+") + label(jump) + "  " + DateUtils.formatElapsedTime(to / 1000)
        }
        pill.animate().cancel()
        pill.alpha = 1f
        pill.isVisible = true
        pill.bringToFront()
        val view = player.view ?: return
        view.findViewById<DefaultTimeBar>(R.id.progressBar)?.setPosition(to)
        view.findViewById<TextView>(R.id.position)?.text = DateUtils.formatElapsedTime(to / 1000)
    }

    private fun hide(player: PlayerFragment) {
        val bubble = videoLayout(player)?.findViewWithTag<View>(TAG) ?: return
        bubble.animate().alpha(0f).setStartDelay(300).setDuration(200).withEndAction { bubble.isVisible = false }.start()
    }
}
