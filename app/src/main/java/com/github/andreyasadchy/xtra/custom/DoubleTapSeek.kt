package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Xtra5s custom: YouTube-style double-tap seek on VODs (full-size video, portrait or landscape).
 * Double-tap the left 40% of the video to rewind, the right 40% to fast-forward, by the player's
 * rewind / forward amount (10s by default); keep tapping that side to add more (20s, 30s...),
 * tap the other side to go the other way. A curved panel with a ripple, moving arrows and the
 * total shows on that side. Anywhere else (the middle, live streams, clips) the official
 * double-tap is unchanged. Called first from SoloVolume.onTouch (the video's touch hook).
 */
object DoubleTapSeek {

    internal const val SIDE = 0.4f
    private const val SEEKING_MS = 900L // after a seek, taps on a side keep seeking this long

    /** -1 left side, 1 right side, 0 middle, for [x] across a video [width] wide. */
    internal fun side(x: Float, width: Float): Int = when {
        width <= 0f -> 0
        x < width * SIDE -> -1
        x > width * (1 - SIDE) -> 1
        else -> 0
    }

    /** "10 seconds", "1 minute 30 seconds". */
    internal fun total(seconds: Long): String {
        val m = seconds / 60
        val s = seconds % 60
        val min = if (m == 1L) "1 minute" else "$m minutes"
        val sec = if (s == 1L) "1 second" else "$s seconds"
        return when {
            m == 0L -> sec
            s == 0L -> min
            else -> "$min $sec"
        }
    }

    private var passing = false
    private var consuming = false
    private var lastTapUp = 0L
    private var lastTapSide = 0
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var seekingUntil = 0L
    private var seekSide = 0
    private var seekTotal = 0L
    private var target: Long? = null

    private fun applies(player: PlayerFragment): Boolean {
        val state = player.xtra5PlayerState()
        return state.isMaximized && (state.type == BasePlaybackService.VIDEO || state.type == BasePlaybackService.OFFLINE_VIDEO) &&
            (player.getTotalDuration() ?: 0L) > 0L
    }

    /** True when the touch is a double-tap seek (the player's own handling sees nothing of it). */
    fun onTouch(player: PlayerFragment, view: View, event: MotionEvent): Boolean {
        if (passing) return false
        val layout = player.view?.findViewById<FrameLayout>(R.id.playerLayout) ?: return false
        val loc = IntArray(2).also { layout.getLocationOnScreen(it) }
        val x = event.rawX - loc[0]
        val y = event.rawY - loc[1]
        val inside = x >= 0 && y >= 0 && x < layout.width && y < layout.height
        val config = ViewConfiguration.get(view.context)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                consuming = false
                downX = event.rawX; downY = event.rawY; downTime = event.eventTime
                if (!inside || !applies(player)) { lastTapUp = 0; seekingUntil = 0; return false }
                val side = side(x, layout.width.toFloat())
                val now = event.eventTime
                val seeking = now < seekingUntil && side != 0
                val second = side != 0 && lastTapSide == side && now - lastTapUp < ViewConfiguration.getDoubleTapTimeout() &&
                    hypot(x - lastTapX, y - lastTapY) < config.scaledDoubleTapSlop
                if (seeking || second) {
                    consuming = true
                    // The player's own tap handling saw the first tap: cancel it (no controls toggle, no chat toggle).
                    cancelPlayerTouch(view, event)
                    seek(player, layout, side, x, y)
                    return true
                }
                if (side == 0) seekingUntil = 0
                return false
            }
            MotionEvent.ACTION_UP -> {
                if (consuming) { consuming = false; return true }
                val tap = event.eventTime - downTime < ViewConfiguration.getTapTimeout() * 2 &&
                    abs(event.rawX - downX) < config.scaledTouchSlop && abs(event.rawY - downY) < config.scaledTouchSlop
                if (tap && inside && applies(player)) {
                    lastTapUp = event.eventTime
                    lastTapSide = side(x, layout.width.toFloat())
                    lastTapX = x; lastTapY = y
                } else lastTapUp = 0
                return false
            }
            MotionEvent.ACTION_CANCEL -> { val was = consuming; consuming = false; return was }
            else -> return consuming
        }
    }

    private fun cancelPlayerTouch(view: View, event: MotionEvent) {
        passing = true
        try {
            MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }.let { cancel ->
                view.dispatchTouchEvent(cancel)
                cancel.recycle()
            }
        } finally {
            passing = false
        }
    }

    private fun seek(player: PlayerFragment, layout: FrameLayout, side: Int, x: Float, y: Float) {
        val prefs = layout.context.prefs()
        val step = (prefs.getString(if (side < 0) C.PLAYER_REWIND else C.PLAYER_FORWARD, "10")?.toLongOrNull() ?: 10L).coerceAtLeast(1) * 1000
        val now = SystemClock.uptimeMillis()
        val duration = player.getTotalDuration() ?: return
        // A new run (or the other side): start counting again from here.
        if (now >= seekingUntil || side != seekSide) {
            seekSide = side
            seekTotal = 0
            target = player.getCurrentPosition()
        }
        val from = target ?: player.getCurrentPosition() ?: 0L
        val to = (from + side * step).coerceIn(0L, duration)
        if (to == from) { seekingUntil = now + SEEKING_MS; overlay(layout).show(side, seekTotal, x, y); return }
        seekTotal += abs(to - from) / 1000
        target = to
        player.seek(to)
        seekingUntil = now + SEEKING_MS
        lastTapUp = 0
        layout.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        overlay(layout).show(side, seekTotal, x, y)
    }

    private fun overlay(layout: FrameLayout): SeekOverlay {
        layout.findViewWithTag<SeekOverlay>(SeekOverlay.TAG)?.let { it.bringToFront(); return it }
        return SeekOverlay(layout.context).also {
            layout.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    /** The YouTube-like panel on one side: curved edge, ripple, three moving arrows, the total. */
    private class SeekOverlay(context: Context) : View(context) {
        companion object { const val TAG = "xtra5s_double_tap_seek" }

        init {
            tag = TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        private val dp = resources.displayMetrics.density
        private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
        private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            isFakeBoldText = true
            setShadowLayer(4 * dp, 0f, 0f, 0x80000000.toInt())
        }
        private var side = 0
        private var label = ""
        private var tapX = 0f
        private var tapY = 0f
        private var tapAt = 0L
        private var lastShow = 0L
        private val path = Path()

        fun show(side: Int, totalSeconds: Long, x: Float, y: Float) {
            this.side = side
            label = total(totalSeconds)
            tapX = x; tapY = y
            val now = SystemClock.uptimeMillis()
            tapAt = now
            lastShow = now
            visibility = VISIBLE
            alpha = 1f
            postInvalidateOnAnimation()
        }

        override fun onDraw(canvas: Canvas) {
            if (side == 0) return
            val now = SystemClock.uptimeMillis()
            val sinceLast = now - lastShow
            val fade = if (sinceLast > 650) (1f - (sinceLast - 650) / 250f).coerceIn(0f, 1f) else 1f
            if (fade <= 0f) { side = 0; visibility = GONE; return }
            val w = width.toFloat()
            val h = height.toFloat()
            // The panel: the outer 40% with a curved inner edge (like YouTube).
            val panelWidth = w * SIDE
            path.reset()
            if (side < 0) {
                path.moveTo(0f, 0f)
                path.lineTo(panelWidth * 0.85f, 0f)
                path.quadTo(panelWidth * 1.15f, h / 2, panelWidth * 0.85f, h)
                path.lineTo(0f, h)
            } else {
                path.moveTo(w, 0f)
                path.lineTo(w - panelWidth * 0.85f, 0f)
                path.quadTo(w - panelWidth * 1.15f, h / 2, w - panelWidth * 0.85f, h)
                path.lineTo(w, h)
            }
            path.close()
            panel.alpha = (0x33 * fade).toInt()
            canvas.drawPath(path, panel)
            // The ripple from the tap, clipped to the panel.
            val rippleT = ((now - tapAt) / 450f).coerceIn(0f, 1f)
            if (rippleT < 1f) {
                canvas.save()
                canvas.clipPath(path)
                ripple.alpha = (0x40 * (1f - rippleT) * fade).toInt()
                canvas.drawCircle(tapX, tapY, max(w, h) * 0.35f * rippleT, ripple)
                canvas.restore()
            }
            // Three arrows, lighting up one after another, and the total under them.
            val cx = if (side < 0) panelWidth * 0.45f else w - panelWidth * 0.45f
            val cy = h / 2
            val size = 9 * dp
            val gap = 2 * size
            val phase = ((now - tapAt) / 150) % 3
            for (i in 0 until 3) {
                val order = if (side < 0) 2 - i else i
                arrow.alpha = ((if (order.toLong() == phase) 0xFF else 0x55) * fade).toInt()
                val ax = cx + (i - 1) * gap
                val tri = Path()
                if (side < 0) { tri.moveTo(ax - size, cy); tri.lineTo(ax + size * 0.6f, cy - size * 0.9f); tri.lineTo(ax + size * 0.6f, cy + size * 0.9f) }
                else { tri.moveTo(ax + size, cy); tri.lineTo(ax - size * 0.6f, cy - size * 0.9f); tri.lineTo(ax - size * 0.6f, cy + size * 0.9f) }
                tri.close()
                canvas.drawPath(tri, arrow)
            }
            text.alpha = (0xFF * fade).toInt()
            canvas.drawText(label, cx, cy + size + text.textSize * 1.4f, text)
            postInvalidateOnAnimation()
        }

    }
}
