package com.github.andreyasadchy.xtra.custom

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.media3.ui.DefaultTimeBar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.BasePlaybackService
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Xtra5 custom: VOD / clip timeline extras.
 *  - Chapter markers: on VODs with chapters, a marker for each chapter start on the seek bar.
 *  - Persistent progress bar (Settings > Player): on VODs, downloads and clips in fullscreen
 *    (landscape), a copy of the seek bar stays in exactly the same place when the controls
 *    hide: full opacity, then faded to the chosen opacity 4 seconds after the controls were last
 *    shown (same timing as the battery percentage). Display only: touches pass through.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Started from UpstreamUpdates.onMainActivityCreate; player info from PlayerFragment.xtra5PlayerState.
 */
object ProgressOverlay {

    const val PREF_ENABLED = "xtra5_progress_bar_show"
    const val PREF_FADED_OPACITY = "xtra5_progress_bar_faded_opacity"
    private const val FADE_AFTER_MS = 4000L
    private const val TICK_MS = 1000L

    fun register(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f is PlayerFragment) attach(f, v)
            }
        }, true)
    }

    /** A seek bar that only shows progress: touches go to the player underneath. */
    private class DisplayTimeBar(context: Context) : DefaultTimeBar(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean = false
    }

    private class State(val player: PlayerFragment, val container: FrameLayout, val controls: View, val realBar: DefaultTimeBar, val bar: DisplayTimeBar) {
        val handler = Handler(Looper.getMainLooper())
        var chapters: List<Long> = emptyList()
        var lastControlsVisible: Boolean? = null
        var controlsShownAt = 0L
        var attached = false
        val fade = Runnable { bar.animate().alpha(fadedAlpha()).setDuration(400L).start() }
        val tick = object : Runnable {
            override fun run() {
                if (!attached) return
                update(this@State)
                handler.postDelayed(this, TICK_MS)
            }
        }

        fun fadedAlpha() = bar.context.prefs().getInt(PREF_FADED_OPACITY, 40).coerceIn(0, 100) / 100f
    }

    private fun attach(player: PlayerFragment, view: View) {
        val container = view.findViewById<FrameLayout>(R.id.playerLayout) ?: return
        val controls = view.findViewById<View>(R.id.playerControls) ?: return
        val realBar = view.findViewById<DefaultTimeBar>(R.id.progressBar) ?: return
        if (container.findViewById<View>(R.id.xtra5_progress_bar) != null) return
        val bar = DisplayTimeBar(view.context).apply {
            id = R.id.xtra5_progress_bar
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        container.addView(bar, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.WRAP_CONTENT))
        val state = State(player, container, controls, realBar, bar)
        // Same place as the real seek bar: copy its position whenever it is laid out.
        realBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> matchPosition(state) }
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = start(state)
            override fun onViewDetachedFromWindow(v: View) {
                state.attached = false
                state.handler.removeCallbacksAndMessages(null)
                bar.removeCallbacks(state.fade)
            }
        })
        if (container.isAttachedToWindow) start(state)
        // Controls shown / hidden, fullscreen changes.
        container.viewTreeObserver.addOnGlobalLayoutListener { if (state.attached) update(state) }
    }

    private fun start(state: State) {
        if (state.attached) return
        state.attached = true
        state.handler.removeCallbacks(state.tick)
        state.handler.post(state.tick)
    }

    private fun matchPosition(state: State) {
        val real = state.realBar
        if (!real.isShown || real.width == 0) return
        val realLoc = IntArray(2).also { real.getLocationInWindow(it) }
        val boxLoc = IntArray(2).also { state.container.getLocationInWindow(it) }
        val params = state.bar.layoutParams as FrameLayout.LayoutParams
        val left = realLoc[0] - boxLoc[0]
        val top = realLoc[1] - boxLoc[1]
        if (params.width != real.width || params.height != real.height || params.leftMargin != left || params.topMargin != top) {
            params.width = real.width
            params.height = real.height
            params.leftMargin = left
            params.topMargin = top
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            state.bar.layoutParams = params
        }
    }

    private fun update(state: State) {
        val player = state.player
        if (!player.isAdded || player.view == null) return
        val ps = player.xtra5PlayerState()
        val bar = state.bar
        // Chapter markers on both bars (VODs with chapters).
        val chapters = if (ps.type == BasePlaybackService.VIDEO || ps.type == BasePlaybackService.OFFLINE_VIDEO) ps.chapters.filter { it > 0 }.sorted() else emptyList()
        if (chapters != state.chapters) {
            state.chapters = chapters
            val times = chapters.toLongArray()
            val played = BooleanArray(times.size)
            state.realBar.setAdGroupTimesMs(times, played, times.size)
            bar.setAdGroupTimesMs(times, played, times.size)
        }
        val context = bar.context
        val show = context.prefs().getBoolean(PREF_ENABLED, false) &&
            ps.isMaximized && player.isResumed &&
            (ps.type == BasePlaybackService.VIDEO || ps.type == BasePlaybackService.OFFLINE_VIDEO || ps.type == BasePlaybackService.CLIP) &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            (state.bar.layoutParams.width > 0)
        val duration = player.getTotalDuration()?.takeIf { it > 0 }
        val controlsVisible = state.controls.isVisible
        if (!show || duration == null || controlsVisible) {
            // The real seek bar is showing (or nothing should be).
            if (bar.isVisible) {
                bar.visibility = View.GONE
                bar.removeCallbacks(state.fade)
                bar.animate().cancel()
            }
            if (controlsVisible && state.lastControlsVisible != true) state.controlsShownAt = System.currentTimeMillis()
            state.lastControlsVisible = controlsVisible
            if (controlsVisible) matchPosition(state)
            return
        }
        bar.setDuration(duration)
        bar.setPosition(player.getCurrentPosition() ?: 0L)
        if (!bar.isVisible || state.lastControlsVisible != false) {
            // Controls just hid (or the bar just turned on): full opacity, then fade.
            bar.animate().cancel()
            bar.alpha = 1f
            bar.visibility = View.VISIBLE
            bar.removeCallbacks(state.fade)
            val elapsed = System.currentTimeMillis() - state.controlsShownAt
            bar.postDelayed(state.fade, (FADE_AFTER_MS - elapsed).coerceAtLeast(0L))
        }
        state.lastControlsVisible = false
    }
}
