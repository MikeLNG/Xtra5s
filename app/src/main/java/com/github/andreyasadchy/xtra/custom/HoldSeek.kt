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
 * hours (most of the drag goes to the small steps), and Start / End in the last ~200 pixels of
 * the screen (a tick at each step). The video keeps playing meanwhile; a
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

    private const val FINE_LAST = 6 // 1m
    private const val MID_LAST = 14 // 20m

    /**
     * Where step [index] starts, as a part (0-1) of the drag range (half the screen's width),
     * with [left] seconds before the start / end: the fine steps (2s to 1m) share the first half
     * evenly, 1m 30s to 20m the next 30%, and the bigger steps that fit before the start / end
     * the rest. Null for a step that can't be reached (past the start / end).
     */
    internal fun threshold(index: Int, left: Long): Float? {
        if (index < 0 || index >= STEPS.size || STEPS[index] >= left) return null
        if (index <= FINE_LAST) return 0.5f * index / (FINE_LAST + 1)
        if (index <= MID_LAST) return 0.5f + 0.3f * (index - FINE_LAST - 1) / (MID_LAST - FINE_LAST)
        val coarse = (MID_LAST + 1 until STEPS.size).count { STEPS[it] < left }
        return 0.8f + 0.2f * (index - MID_LAST - 1) / coarse.coerceAtLeast(1)
    }

    /**
     * The jump in seconds (negative = back) for a drag of [dx] pixels over a drag range of
     * [range] pixels (the same wherever the drag starts), with [dead] pixels of cancel zone, when
     * [backSeconds] / [forwardSeconds] are left before the start / end. Start / End only when the
     * finger is [inEdgeZone] (the last pixels of the screen on that side) after dragging past the
     * fine steps; otherwise never further than the biggest step that fits.
     */
    internal fun jumpFor(dx: Float, range: Float, dead: Float, inEdgeZone: Boolean, backSeconds: Long, forwardSeconds: Long): Long {
        val distance = abs(dx)
        if (distance < dead) return 0
        val left = if (dx < 0) backSeconds else forwardSeconds
        if (left <= 0) return 0
        val part = distance / range.coerceAtLeast(1f)
        val jump = if (inEdgeZone && part >= 0.5f) left else {
            var found = 0L
            for (i in STEPS.indices) {
                val t = threshold(i, left) ?: break
                if (part < t) break
                found = STEPS[i]
            }
            if (found == 0L) STEPS[0].coerceAtMost(left) else found
        }
        return if (dx < 0) -jump else jump
    }

    /** How much of its side the pill's gold fill covers (0-1), for [jump] out of [left] seconds. */
    internal fun fill(jump: Long, left: Long): Float {
        val size = abs(jump)
        if (size == 0L) return 0f
        if (size >= left) return 1f
        val index = STEPS.indexOfFirst { it == size }.takeIf { it >= 0 } ?: return 0f
        return ((threshold(index, left) ?: 0f) * 0.9f).coerceIn(0.04f, 0.9f)
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

    fun begin(player: PlayerFragment, rawX: Float) {
        startX = rawX
        duration = player.getTotalDuration() ?: 0L
        startPosition = (player.getCurrentPosition() ?: 0L).coerceIn(0L, duration)
        jump = 0L
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
        val inEdgeZone = if (dx < 0) rawX <= EDGE_PX else rawX >= screen - EDGE_PX
        val dead = DEAD_ZONE_DP * layout.resources.displayMetrics.density
        val newJump = jumpFor(dx, screen / 2f, dead, inEdgeZone, startPosition / 1000, (duration - startPosition) / 1000)
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
