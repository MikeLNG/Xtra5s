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
 * (a short buzz), then drag: left rewinds, right fast-forwards, in steps from 10 seconds up to
 * 20 minutes (a tick at each step). The video keeps playing meanwhile; a bubble shows the jump
 * and where it lands, and the seek bar's marker waits there in gold. Letting go jumps there;
 * dragging back to where you started first cancels. Elsewhere on the video, holding is the
 * volume slide ([SoloVolume], which starts this one).
 */
object HoldSeek {

    /** The jumps, in seconds. */
    internal val STEPS = intArrayOf(10, 20, 30, 45, 60, 90, 120, 180, 300, 420, 600, 900, 1200)

    /** Bottom part of the video that seeks instead of changing the volume. */
    internal const val BAND = 0.25f

    /** Drags shorter than this (part of half the video's width) don't seek: a cancel. */
    internal const val DEAD_ZONE = 0.04f

    /**
     * The jump (seconds, negative = back) for a drag of [fraction] of half the video's width.
     * The first steps are close together (small jumps are easy to stop at); 20 minutes is
     * reached at about 90% of half the width.
     */
    internal fun jumpFor(fraction: Float): Int {
        val distance = abs(fraction)
        if (distance < DEAD_ZONE) return 0
        var index = 0
        for (i in STEPS.indices) {
            val threshold = DEAD_ZONE + (1f - DEAD_ZONE) * (i / STEPS.size.toFloat()).pow(1.25f)
            if (distance >= threshold) index = i
        }
        return if (fraction < 0) -STEPS[index] else STEPS[index]
    }

    /** "30s", "1m 30s", "20m". */
    internal fun label(seconds: Int): String {
        val s = abs(seconds)
        val m = s / 60
        val rest = s % 60
        return when {
            m == 0 -> "${rest}s"
            rest == 0 -> "${m}m"
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
    private var jump = 0

    fun begin(player: PlayerFragment, rawX: Float) {
        startX = rawX
        duration = player.getTotalDuration() ?: 0L
        startPosition = (player.getCurrentPosition() ?: 0L).coerceIn(0L, duration)
        jump = 0
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
        val newJump = jumpFor((rawX - startX) / (layout.width / 2f).coerceAtLeast(1f))
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
        if (jump != 0) {
            player.seek(target())
        } else {
            val position = player.getCurrentPosition() ?: startPosition
            bar?.setPosition(position)
            view?.findViewById<TextView>(R.id.position)?.text = DateUtils.formatElapsedTime(position / 1000)
        }
        jump = 0
        player.xtra5PlayerState().showController() // restarts the normal auto-hide
        hide(player)
    }

    private fun target() = (startPosition + jump * 1000L).coerceIn(0L, duration)

    private fun videoLayout(player: PlayerFragment): FrameLayout? = player.view?.findViewById(R.id.playerLayout)

    // ---- The bubble (middle of the video) and the held marker ----

    private const val TAG = "xtra5s_seek_bubble"

    private fun show(player: PlayerFragment) {
        val layout = videoLayout(player) ?: return
        val dp = layout.resources.displayMetrics.density
        val bubble = layout.findViewWithTag<TextView>(TAG) ?: TextView(layout.context).apply {
            tag = TAG
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            val pad = (12 * dp).toInt()
            setPadding((18 * dp).toInt(), pad, (18 * dp).toInt(), pad)
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = 24 * dp }
            elevation = 8 * dp
            layout.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        val to = target()
        bubble.animate().cancel()
        bubble.text = if (jump == 0) "Drag ◀ ▶ to seek" else (if (jump < 0) "−" else "+") + label(jump) + "  →  " + DateUtils.formatElapsedTime(to / 1000)
        bubble.alpha = 1f
        bubble.isVisible = true
        bubble.bringToFront()
        val view = player.view ?: return
        view.findViewById<DefaultTimeBar>(R.id.progressBar)?.setPosition(to)
        view.findViewById<TextView>(R.id.position)?.text = DateUtils.formatElapsedTime(to / 1000)
    }

    private fun hide(player: PlayerFragment) {
        val bubble = videoLayout(player)?.findViewWithTag<View>(TAG) ?: return
        bubble.animate().alpha(0f).setStartDelay(300).setDuration(200).withEndAction { bubble.isVisible = false }.start()
    }
}
