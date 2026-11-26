package com.github.andreyasadchy.xtra.custom

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.abs

/**
 * Xtra5s custom: move the battery percentage by touching and holding it (half a second, a buzz
 * and a gold outline), then dragging; it snaps to the edges and centre lines and stays inside the
 * video. The spot is saved relative to the video (centre of the label, 0-1 across and down), so
 * it's the same on any screen. Settings > Player > Battery percentage > Reset position puts it
 * back beside the ⋮ menu. The player's label is handled from SoloVolume.onTouch (the video's
 * touch hook); Dual Stream's has its own spot and listens on the label itself.
 */
object BatteryMove {

    const val PREF_X = "xtra5s_battery_x"
    const val PREF_Y = "xtra5s_battery_y"
    const val PREF_DUAL_X = "xtra5s_battery_dual_x"
    const val PREF_DUAL_Y = "xtra5s_battery_dual_y"
    private const val HOLD_MS = 500L
    private const val SNAP_DP = 14
    private const val EDGE_DP = 8
    private const val GRAB_DP = 14

    /** The saved spot (centre, 0-1), or null for the default place. */
    fun saved(view: View, keyX: String, keyY: String): Pair<Float, Float>? {
        val prefs = view.context.prefs()
        val x = prefs.getFloat(keyX, -1f)
        val y = prefs.getFloat(keyY, -1f)
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }

    /**
     * The label's centre along one side ([total] pixels) for a finger at [centre]: kept inside
     * (with an [edge] gap), and snapped to the edges or the middle when within [snap].
     */
    internal fun snap(centre: Float, size: Float, total: Float, edge: Float, snap: Float): Float {
        val min = edge + size / 2
        val max = total - edge - size / 2
        if (max <= min) return total / 2
        val c = centre.coerceIn(min, max)
        return listOf(min, total / 2, max).firstOrNull { abs(c - it) <= snap } ?: c
    }

    /** Puts [text] with its centre at [pos] (0-1) inside [container]. */
    fun place(text: TextView, container: View, pos: Pair<Float, Float>) {
        if (text.width == 0 || container.width == 0) return
        val left = (pos.first * container.width - text.width / 2f).toInt().coerceIn(0, (container.width - text.width).coerceAtLeast(0))
        val top = (pos.second * container.height - text.height / 2f).toInt().coerceIn(0, (container.height - text.height).coerceAtLeast(0))
        val params = text.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.gravity != (Gravity.TOP or Gravity.START) || params.leftMargin != left || params.topMargin != top || params.marginEnd != 0) {
            params.gravity = Gravity.TOP or Gravity.START
            params.leftMargin = left
            params.topMargin = top
            params.marginEnd = 0
            params.rightMargin = 0
            text.layoutParams = params
        }
    }

    // ---- One move at a time ----

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var moving: TextView? = null
    private var owning = false
    private var passing = false
    private var downX = 0f
    private var downY = 0f
    private var down: MotionEvent? = null

    private fun start(text: TextView) {
        moving = text
        text.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val dp = text.resources.displayMetrics.density
        text.background = GradientDrawable().apply {
            cornerRadius = 6 * dp
            setStroke((2 * dp).toInt(), SideBySide.GOLD)
            setColor(0x33000000)
        }
        text.alpha = 1f
    }

    private fun follow(text: TextView, container: View, rawX: Float, rawY: Float) {
        val dp = text.resources.displayMetrics.density
        val loc = IntArray(2).also { container.getLocationOnScreen(it) }
        val x = snap(rawX - loc[0], text.width.toFloat(), container.width.toFloat(), EDGE_DP * dp, SNAP_DP * dp)
        val y = snap(rawY - loc[1], text.height.toFloat(), container.height.toFloat(), EDGE_DP * dp, SNAP_DP * dp)
        place(text, container, x / container.width to y / container.height)
    }

    private fun finish(text: TextView, container: View, keyX: String, keyY: String) {
        text.background = null
        val params = text.layoutParams as? FrameLayout.LayoutParams
        if (params != null && container.width > 0 && container.height > 0) {
            val x = (params.leftMargin + text.width / 2f) / container.width
            val y = (params.topMargin + text.height / 2f) / container.height
            text.context.prefs().edit { putFloat(keyX, x.coerceIn(0f, 1f)); putFloat(keyY, y.coerceIn(0f, 1f)) }
        }
        moving = null
    }

    private fun clear() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        owning = false
        down?.recycle()
        down = null
    }

    /**
     * The player's video touches, first: a hold on the battery label moves it. True when the
     * touch is the label's (the player sees nothing of it). A tap on it shows / hides the
     * controls as usual; a drag that starts on it before the hold goes to the player.
     */
    fun onTouch(player: PlayerFragment, view: View, event: MotionEvent): Boolean {
        if (passing) return false
        val container = player.view?.findViewById<FrameLayout>(R.id.playerLayout) ?: return false
        val text = container.findViewById<TextView>(R.id.xtra5_battery) ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                clear()
                if (text.visibility != View.VISIBLE || text.width == 0 || SoloVolume.overButton(player, event)) return false
                val dp = text.resources.displayMetrics.density
                val loc = IntArray(2).also { text.getLocationOnScreen(it) }
                val grab = GRAB_DP * dp
                val inside = event.rawX >= loc[0] - grab && event.rawX <= loc[0] + text.width + grab &&
                    event.rawY >= loc[1] - grab && event.rawY <= loc[1] + text.height + grab
                if (!inside) return false
                owning = true
                downX = event.rawX; downY = event.rawY
                down = MotionEvent.obtain(event)
                pending = Runnable { pending = null; start(text) }.also { handler.postDelayed(it, HOLD_MS) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (moving === text) { follow(text, container, event.rawX, event.rawY); return true }
                if (!owning) return false
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                    // Not a hold: hand the gesture to the player as it happened.
                    val first = down
                    clear()
                    passing = true
                    try {
                        first?.let { view.dispatchTouchEvent(it) }
                        view.dispatchTouchEvent(event)
                    } finally {
                        passing = false
                        first?.recycle()
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moving === text) { finish(text, container, PREF_X, PREF_Y); clear(); return true }
                if (!owning) return false
                clear()
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    // A tap on the label: what a tap on the video does.
                    val state = player.xtra5PlayerState()
                    val controls = player.view?.findViewById<View>(R.id.playerControls)
                    if (controls?.visibility == View.VISIBLE) state.hideController() else state.showController()
                }
                return true
            }
            else -> return owning || moving === text
        }
    }

    /** Dual Stream: the label listens itself (it sits above both videos). */
    fun attachStandalone(text: TextView, container: View, onMoved: () -> Unit) {
        text.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    clear()
                    downX = event.rawX; downY = event.rawY
                    pending = Runnable { pending = null; start(text) }.also { handler.postDelayed(it, HOLD_MS) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (moving === text) follow(text, container, event.rawX, event.rawY)
                    else {
                        val slop = ViewConfiguration.get(text.context).scaledTouchSlop
                        if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) clear()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moving === text) { finish(text, container, PREF_DUAL_X, PREF_DUAL_Y); onMoved() }
                    clear()
                    true
                }
                else -> true
            }
        }
    }
}

/** Settings > Player > Battery percentage > Reset position. */
@androidx.annotation.Keep
class BatteryPositionResetPreference @JvmOverloads constructor(context: android.content.Context, attrs: android.util.AttributeSet? = null) :
    androidx.preference.Preference(context, attrs) {

    override fun onAttached() {
        super.onAttached()
        update()
    }

    override fun onClick() {
        context.prefs().edit {
            remove(BatteryMove.PREF_X); remove(BatteryMove.PREF_Y); remove(BatteryMove.PREF_DUAL_X); remove(BatteryMove.PREF_DUAL_Y)
        }
        android.widget.Toast.makeText(context, "Battery percentage back beside the ⋮ menu", android.widget.Toast.LENGTH_SHORT).show()
        update()
    }

    private fun update() {
        val prefs = context.prefs()
        val moved = prefs.contains(BatteryMove.PREF_X) || prefs.contains(BatteryMove.PREF_DUAL_X)
        summary = if (moved) "Moved. Tap to put it back beside the ⋮ menu (hold the label in the player to move it)" else
            "Default place, beside the ⋮ menu. Hold the label in the player to move it"
    }
}
