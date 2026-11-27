package com.github.andreyasadchy.xtra.custom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.BatteryManager
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Xtra5 custom: battery percentage in fullscreen video (Settings > Player > Battery percentage).
 *
 * Shown at the top-right corner of the video, right of the ⋮ menu (the top-right player buttons
 * move left to make room), only while the player is full-size in fullscreen (landscape). It stays
 * in place when the controls hide: it fades in and out together with the controls, between full
 * opacity (controls showing) and the chosen faded opacity (controls hidden). A white bolt shows while charging. Hidden
 * on devices without a battery and whenever TV mode shortcuts are on.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Started from UpstreamUpdates.onMainActivityCreate (no extra upstream hook).
 */
object BatteryIndicator {

    const val PREF_ENABLED = "xtra5_battery_show"
    const val PREF_FADED_OPACITY = "xtra5_battery_faded_opacity"
    private const val FADE_AFTER_MS = 4000L

    fun register(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f is PlayerFragment) attach(f, v)
            }
        }, true)
    }

    private class Indicator(val player: PlayerFragment, val text: TextView, val container: FrameLayout, val controls: View, val buttons: View) {
        var controlsShownAt = 0L
        var lastControlsVisible = false
        val originalButtonsMarginEnd = (buttons.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd ?: 0
        /** Xtra5s: the label's left edge when it sits in the button row (last known). */
        var rowLeft: Int? = null
        val fade = Runnable { text.animate().alpha(fadedAlpha()).setDuration(400L).start() }

        fun fadedAlpha() = text.context.prefs().getInt(PREF_FADED_OPACITY, 40).coerceIn(0, 100) / 100f
    }

    private fun attach(player: PlayerFragment, view: View) {
        val container = view.findViewById<FrameLayout>(R.id.playerLayout) ?: return
        val controls = view.findViewById<View>(R.id.playerControls) ?: return
        val buttons = view.findViewById<View>(R.id.topRightLayout) ?: return
        if (container.findViewById<View>(R.id.xtra5_battery) != null) return
        val context = view.context
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val text = TextView(context).apply {
            id = R.id.xtra5_battery
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setShadowLayer(3f, 0f, 0f, 0x99000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(2)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
        }
        container.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            marginEnd = dp(10)
        })
        val indicator = Indicator(player, text, container, controls, buttons)

        // Battery level / charging state.
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = updateBattery(indicator, intent)
        }
        var listening = false
        fun startListening() {
            if (listening) return
            listening = true
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { updateBattery(indicator, it) }
        }
        text.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = startListening()

            override fun onViewDetachedFromWindow(v: View) {
                if (listening) {
                    listening = false
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                text.removeCallbacks(indicator.fade)
            }
        })
        // The player is usually already on screen when this runs, so the attach callback above
        // has already happened for the text: start reading the battery now.
        if (text.isAttachedToWindow) startListening()
        // Fullscreen / controls changes.
        container.viewTreeObserver.addOnGlobalLayoutListener { update(indicator) }
        // Every frame the controls fade in or out, match their opacity.
        container.viewTreeObserver.addOnPreDrawListener {
            if (text.isVisible) applyAlpha(indicator)
            true
        }
    }

    // ---- Xtra5s: Dual Stream ----

    /**
     * Battery percentage for the Dual Stream screen: top-right corner of [overlay] (the whole
     * screen), in both orientations. Same settings as in the player: shown if Battery percentage
     * is on (and TV mode shortcuts are off); full opacity after any touch or key press ([wake]),
     * fading to the faded opacity after the same inactive time.
     */
    class Standalone(val text: TextView) {
        val fade = Runnable { text.animate().alpha(fadedAlpha()).setDuration(400L).start() }
        fun fadedAlpha() = text.context.prefs().getInt(PREF_FADED_OPACITY, 40).coerceIn(0, 100) / 100f

        /** Any touch or key press: full opacity, then fade after the inactive time. */
        fun wake() {
            if (!text.isVisible) return
            text.removeCallbacks(fade)
            text.animate().cancel()
            text.alpha = 1f
            text.postDelayed(fade, FADE_AFTER_MS)
        }
    }

    fun attachStandalone(overlay: FrameLayout): Standalone? {
        val context = overlay.context
        val prefs = context.prefs()
        if (!prefs.getBoolean(PREF_ENABLED, false) || prefs.getBoolean(TvShortcuts.PREF_ENABLED, false)) return null
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        val text = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setShadowLayer(3f, 0f, 0f, 0x99000000.toInt())
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(2)
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            elevation = dp(6).toFloat()
        }
        overlay.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            topMargin = dp(4)
            marginEnd = dp(8)
        })
        val standalone = Standalone(text)
        fun apply(intent: Intent) {
            val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (!present || level < 0 || scale <= 0) {
                text.visibility = View.GONE
                return
            }
            text.text = "${level * 100 / scale}%"
            val size = (text.textSize * 1.05f).toInt()
            val bolt = if (charging) context.getDrawable(R.drawable.xtra5_bolt)?.mutate()?.apply {
                setTint(Color.WHITE)
                setBounds(0, 0, size, size)
            } else null
            text.setCompoundDrawables(bolt, null, null, null)
            if (!text.isVisible) {
                text.visibility = View.VISIBLE
                standalone.wake()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = apply(intent)
        }
        var listening = false
        fun start() {
            if (listening) return
            listening = true
            context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { apply(it) }
        }
        text.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = start()
            override fun onViewDetachedFromWindow(v: View) {
                if (listening) {
                    listening = false
                    try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
                }
                text.removeCallbacks(standalone.fade)
            }
        })
        if (text.isAttachedToWindow) start()
        return standalone
    }

    private fun updateBattery(indicator: Indicator, intent: Intent) {
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val text = indicator.text
        text.tag = present && level >= 0 && scale > 0
        if (text.tag == true) {
            text.text = "${level * 100 / scale}%"
            val size = (text.textSize * 1.05f).toInt()
            val bolt = if (charging) text.context.getDrawable(R.drawable.xtra5_bolt)?.mutate()?.apply {
                setTint(Color.WHITE)
                setBounds(0, 0, size, size)
            } else null
            text.setCompoundDrawables(bolt, null, null, null)
        }
        update(indicator)
    }

    private fun update(indicator: Indicator) {
        val text = indicator.text
        val player = indicator.player
        val context = text.context
        val prefs = context.prefs()
        val show = prefs.getBoolean(PREF_ENABLED, false) &&
            !prefs.getBoolean(TvShortcuts.PREF_ENABLED, false) &&
            text.tag == true && player.isAdded &&
            player.xtra5PlayerState().isMaximized &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Xtra5s: its place in the button row (buttons to its right; 0 = right of the ⋮ menu).
        val slot = if (show) BatteryMove.slot(text) else 0
        val inRow = slot > 0 && placeSpacer(indicator, slot)
        if (!inRow) removeSpacer(indicator)
        // Make room right of the ⋮ button (or give it back).
        val params = indicator.buttons.layoutParams as? ViewGroup.MarginLayoutParams
        val wantedMargin = if (show && !inRow) indicator.originalButtonsMarginEnd + text.width + (6 * context.resources.displayMetrics.density).toInt() else indicator.originalButtonsMarginEnd
        if (params != null && params.marginEnd != wantedMargin && (!show || text.width > 0)) {
            params.marginEnd = wantedMargin
            indicator.buttons.layoutParams = params
        }
        if (!show) {
            if (text.isVisible) {
                text.visibility = View.GONE
                text.removeCallbacks(indicator.fade)
            }
            return
        }
        if (!text.isVisible) {
            text.visibility = View.VISIBLE
            text.alpha = 1f
            indicator.lastControlsVisible = !indicator.controls.isVisible // force the state below
        }
        // Line up with the top-right buttons. While the controls are hidden the button row may never
        // have been laid out, so use its layout values (top margin, 40dp buttons) instead of its
        // current position.
        val density = context.resources.displayMetrics.density
        val rowTop = (indicator.buttons.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: (5 * density).toInt()
        val rowHeight = if (indicator.buttons.height > 0) indicator.buttons.height else (40 * density).toInt()
        if (inRow) {
            // Over its spacer in the row. While the controls are hidden the row isn't laid out:
            // keep the last place, so it doesn't jump.
            val spacer = (indicator.buttons as? ViewGroup)?.findViewWithTag<View>(BatteryMove.SPACER_TAG)
            if (spacer != null && spacer.isShown && spacer.width > 0) {
                val loc = IntArray(2).also { spacer.getLocationInWindow(it) }
                val box = IntArray(2).also { indicator.container.getLocationInWindow(it) }
                indicator.rowLeft = loc[0] - box[0] + (spacer.width - text.width) / 2
            }
            val left = indicator.rowLeft
            if (left != null && text.height > 0) {
                val top = (rowTop + (rowHeight - text.height) / 2).coerceAtLeast(0)
                (text.layoutParams as? FrameLayout.LayoutParams)?.let {
                    val start = Gravity.TOP or Gravity.START
                    if (it.topMargin != top || it.gravity != start || it.leftMargin != left || it.marginEnd != 0) {
                        it.gravity = start
                        it.leftMargin = left
                        it.marginEnd = 0
                        it.topMargin = top
                        text.layoutParams = it
                    }
                }
            }
        } else if (text.height > 0) {
            val top = (rowTop + (rowHeight - text.height) / 2).coerceAtLeast(0)
            (text.layoutParams as? FrameLayout.LayoutParams)?.let {
                val default = Gravity.TOP or Gravity.END
                val end = (10 * density).toInt()
                if (it.topMargin != top || it.gravity != default || it.marginEnd != end || it.leftMargin != 0) {
                    it.gravity = default
                    it.leftMargin = 0
                    it.marginEnd = end
                    it.topMargin = top
                    text.layoutParams = it
                }
            }
        }
        applyAlpha(indicator)
    }

    /**
     * Xtra5s: a gap in the button row, [slot] buttons from the right, for the label to sit over.
     * False if the row can't take one.
     */
    private fun placeSpacer(indicator: Indicator, slot: Int): Boolean {
        val row = indicator.buttons as? ViewGroup ?: return false
        val text = indicator.text
        if (text.width == 0) return false
        val gap = (6 * text.resources.displayMetrics.density).toInt()
        val spacer = row.findViewWithTag<View>(BatteryMove.SPACER_TAG) ?: View(row.context).apply {
            tag = BatteryMove.SPACER_TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val buttons = BatteryMove.buttons(row)
        val k = slot.coerceAtMost(buttons.size)
        val anchor = if (k == 0) null else buttons[buttons.size - k] // the spacer goes just left of this
        val currentIndex = row.indexOfChild(spacer)
        var wanted = if (anchor == null) row.childCount else row.indexOfChild(anchor)
        if (currentIndex in 0 until wanted) wanted-- // indices shift once the spacer is out
        if (currentIndex != wanted) {
            if (currentIndex >= 0) row.removeView(spacer)
            row.addView(spacer, wanted.coerceIn(0, row.childCount), android.widget.LinearLayout.LayoutParams(text.width + 2 * gap, 1))
        } else if (spacer.layoutParams.width != text.width + 2 * gap) {
            spacer.layoutParams = spacer.layoutParams.apply { width = text.width + 2 * gap }
        }
        return true
    }

    private fun removeSpacer(indicator: Indicator) {
        val row = indicator.buttons as? ViewGroup ?: return
        row.findViewWithTag<View>(BatteryMove.SPACER_TAG)?.let { spacer -> row.post { (spacer.parent as? ViewGroup)?.removeView(spacer) } }
        indicator.rowLeft = null
    }

    /** Opacity follows the controls' own fade, frame by frame: full while shown, faded when hidden. */
    private fun applyAlpha(indicator: Indicator) {
        val controls = indicator.controls
        val shown = if (controls.isVisible) controls.alpha.coerceIn(0f, 1f) else 0f
        val faded = indicator.fadedAlpha()
        val alpha = if (indicator.text.background != null) 1f else faded + (1f - faded) * shown // Xtra5s: full while being moved
        if (indicator.text.alpha != alpha) indicator.text.alpha = alpha
    }
}
