package com.github.andreyasadchy.xtra.custom

import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.edit
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.prefs
import kotlin.math.abs

/**
 * Xtra5s custom: rearrange the battery percentage within the player's top-right button row.
 * Touch and hold it (half a second, a buzz and a gold outline), then slide it left / right: it
 * slots in between the buttons as you pass them, and they move over to make room. Saved as how
 * many buttons are to its right (0 = far right, right of the ⋮ menu, the default), so it keeps
 * its place when a button comes or goes. Handled from SoloVolume.onTouch (the video's touch hook);
 * BatteryIndicator does the placing.
 */
object BatteryMove {

    const val PREF_SLOT = "xtra5s_battery_slot"
    private const val HOLD_MS = 500L
    private const val GRAB_DP = 14
    const val SPACER_TAG = "xtra5s_battery_spacer"

    /** Buttons to the battery's right while it's being dragged (else null: the saved slot). */
    var dragSlot: Int? = null
        private set

    fun slot(view: View): Int = dragSlot ?: view.context.prefs().getInt(PREF_SLOT, 0).coerceAtLeast(0)

    /** The slot for a finger at [fingerX]: how many buttons (by their centres) are right of it. */
    internal fun slotFor(fingerX: Float, centres: List<Float>): Int = centres.count { it > fingerX }

    /** The row's buttons that show, left to right (not the battery's spacer). */
    fun buttons(row: ViewGroup): List<View> =
        (0 until row.childCount).map { row.getChildAt(it) }.filter { it.tag != SPACER_TAG && it.visibility == View.VISIBLE }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var moving: TextView? = null
    private var owning = false
    private var passing = false
    private var downX = 0f
    private var downY = 0f
    private var down: MotionEvent? = null

    private fun start(player: PlayerFragment, text: TextView) {
        moving = text
        dragSlot = text.context.prefs().getInt(PREF_SLOT, 0).coerceAtLeast(0)
        text.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val dp = text.resources.displayMetrics.density
        text.background = GradientDrawable().apply {
            cornerRadius = 6 * dp
            setStroke((2 * dp).toInt(), SideBySide.GOLD)
            setColor(0x33000000)
        }
        text.alpha = 1f
        // The buttons must show to slot in between them.
        player.xtra5PlayerState().showController()
    }

    private fun follow(player: PlayerFragment, text: TextView, rawX: Float) {
        val row = player.view?.findViewById<ViewGroup>(R.id.topRightLayout) ?: return
        if (!row.isShown) return
        val centres = buttons(row).map { val loc = IntArray(2).also { l -> it.getLocationOnScreen(l) }; loc[0] + it.width / 2f }
        val slot = slotFor(rawX, centres)
        if (slot != dragSlot) {
            dragSlot = slot
            text.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            row.requestLayout() // BatteryIndicator moves the spacer and the label
        }
        player.xtra5PlayerState().showController() // keep the controls up while dragging
    }

    private fun finish(player: PlayerFragment, text: TextView) {
        text.background = null
        dragSlot?.let { slot -> text.context.prefs().edit { putInt(PREF_SLOT, slot) } }
        dragSlot = null
        moving = null
        player.xtra5PlayerState().showController() // the normal auto-hide again
        player.view?.findViewById<View>(R.id.topRightLayout)?.requestLayout()
    }

    private fun clear() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
        owning = false
        down?.recycle()
        down = null
    }

    /**
     * The player's video touches, first: a hold on the battery label rearranges it. True when the
     * touch is the label's (the player sees nothing of it). A tap on it shows / hides the controls
     * as usual; a drag that starts on it before the hold goes to the player.
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
                pending = Runnable { pending = null; start(player, text) }.also { handler.postDelayed(it, HOLD_MS) }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (moving === text) { follow(player, text, event.rawX); return true }
                if (!owning) return false
                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                if (abs(event.rawX - downX) > slop || abs(event.rawY - downY) > slop) {
                    // Not a hold: hand the gesture to the player as it happened.
                    val first = down
                    down = null
                    clear()
                    passing = true
                    try {
                        first?.let { view.dispatchTouchEvent(it) }
                        view.dispatchTouchEvent(event)
                    } finally {
                        passing = false
                        first?.recycle()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (moving === text) { finish(player, text); clear(); return true }
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
}
