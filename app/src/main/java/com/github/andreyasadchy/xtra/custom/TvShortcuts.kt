package com.github.andreyasadchy.xtra.custom

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.media3.ui.DefaultTimeBar
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.prefs
import java.lang.ref.WeakReference

/**
 * Xtra5 custom: remote / D-pad shortcuts in the full-screen player.
 *
 * Only active when enabled in Settings, the player is maximized and its on-screen controls are hidden:
 *   Left / Right  rewind / fast-forward by a fixed step and show the controls (VODs, clips and
 *                 downloads; not live streams)
 *   Up            show the controls and "hold" the seek bar (live streams: just show the controls)
 *   Down          open the Chapters menu, if the VOD has chapters (live streams: show / hide chat)
 * While the seek bar is held (like a finger on the marker):
 *   Left / Right  move the marker by a percentage of the video length; hold to keep moving
 *   OK            let go: seek to the marker
 *                 (cancels by itself after 6 seconds without input)
 *   Back          cancel; if the marker hasn't moved, also hide the controls
 * While the controls are up because of a seek (step or letting go of the seek bar): Left / Right
 * skip by the step, Back hides the controls; Up / Down are Android's normal navigation of the
 * controls (Xtra5s: the custom jumps onto the player buttons were removed). The held seek bar's
 * marker is gold and larger.
 * Holding a button counts as one press.
 * Long-press Back (player full-size or minimized): close the player, like swiping it away.
 * Back with the player minimized: close the player, like swiping it away.
 * Long-press OK with no video or the player minimized: refresh every section of the screen.
 * Long-press OK (live stream, controls hidden): reload the stream, like Restart player.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: MainActivity.dispatchKeyEvent and PlayerFragment.xtra5PlayerState.
 */
object TvShortcuts {

    const val PREF_ENABLED = "xtra5_tv_shortcuts"
    const val PREF_SEEK_STEP = "xtra5_tv_seek_step"
    const val PREF_SCRUB_PERCENT = "xtra5_tv_scrub_percent"
    private const val DEFAULT_SEEK_STEP = 10
    private const val DEFAULT_SCRUB_PERCENT = 5
    private const val SCRUB_TIMEOUT_MS = 6000L
    private const val HOLD_DELAY_MS = 400L // holding Left / Right in held mode: start repeating after
    private const val HOLD_REPEAT_MS = 150L // then one step every
    private const val LONG_PRESS_REPEATS = 8 // fallback if a remote never flags the long press
    private const val HELD_SCRUBBER = 0xFFFFC107.toInt() // gold

    /** Player state the shortcuts need; built by PlayerFragment.xtra5PlayerState(). */
    class PlayerState(
        val isMaximized: Boolean,
        val isLiveStream: Boolean,
        val hasChapters: Boolean,
        val showController: () -> Unit,
        val showChapters: () -> Unit,
        val hideController: () -> Unit = {},
        /** Shows / hides the chat beside the video; false if chat is off. */
        val toggleChat: () -> Boolean = { false },
        /** BasePlaybackService type (stream, video, clip, offlineVideo). */
        val type: String? = null,
        /** VOD chapter start times (ms). */
        val chapters: List<Long> = emptyList(),
    )

    /** Keys whose ACTION_DOWN we consumed, so their ACTION_UP is consumed too. */
    private val consumed = HashSet<Int>()

    // Seek bar "held" state (only while scrubbing).
    private class Scrub(val player: WeakReference<PlayerFragment>, val showController: () -> Unit, val hideController: () -> Unit, val duration: Long, var position: Long) {
        val start = position
    }
    private var scrub: Scrub? = null
    /**
     * Player whose controls are showing because of a seek (Left / Right, or letting go of the seek
     * bar). Until they hide: Left / Right skip by the seek step, Up holds the seek bar again and
     * Back hides the controls.
     */
    private var afterSeek: WeakReference<PlayerFragment>? = null
    private var backHidControls = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { endScrub(commit = false) }

    private val dpadKeys = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)
    private val okKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)

    /** Returns true if the key event was handled and should not reach the rest of the app. */
    fun handle(activity: Activity, playerFragment: Fragment?, event: KeyEvent): Boolean {
        AudioGuard.onAppKey(event) // remote media keys may still pause live streams
        val keyCode = event.keyCode
        val scrubbing = scrub != null
        val isBack = keyCode == KeyEvent.KEYCODE_BACK
        if (keyCode !in dpadKeys && !isBack && keyCode !in okKeys) {
            return if (event.action == KeyEvent.ACTION_UP) consumed.remove(keyCode) else false
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (keyCode == holdKey) stopHold()
            return consumed.remove(keyCode)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false

        // Long-press Back: close the player completely, like swiping it away.
        if (isBack && !scrubbing && event.repeatCount > 0 && (!consumed.contains(keyCode) || backHidControls) &&
            (event.isLongPress || event.repeatCount >= LONG_PRESS_REPEATS)) {
            if (closePlayer(activity, playerFragment)) {
                backHidControls = false
                consumed.add(keyCode)
                return true
            }
        }
        if (isBack && event.repeatCount == 0) backHidControls = false
        // Back while the controls are up because of a seek: hide them straight away.
        if (isBack && !scrubbing && event.repeatCount == 0 && activity.prefs().getBoolean(PREF_ENABLED, false)) {
            val player = playerFragment as? PlayerFragment
            val controls = player?.view?.findViewById<View>(R.id.playerControls)
            if (player != null && afterSeek?.get() === player && controls?.isVisible == true) {
                afterSeek = null
                player.xtra5PlayerState().hideController()
                backHidControls = true
                consumed.add(keyCode)
                return true
            }
        }
        // Back with the player minimized: close it, like swiping it away.
        if (isBack && !scrubbing && event.repeatCount == 0 && isMinimized(activity, playerFragment) &&
            closePlayer(activity, playerFragment)) {
            consumed.add(keyCode)
            return true
        }
        if (isBack && !scrubbing) return consumed.contains(keyCode) // short press: normal Back

        // Long-press OK:
        //   live stream full-size (controls hidden): reload it, like Restart player
        //   no video, or the video minimized: refresh every section of the current screen
        if (keyCode in okKeys && !scrubbing) {
            if (event.repeatCount == 0) {
                // Holding OK refreshes here, so stop the focused item's own long-press
                // (e.g. a video card's Download dialog). A short press still clicks it.
                if (canRefreshScreen(activity, playerFragment)) {
                    handler.post { activity.currentFocus?.cancelLongPress() }
                }
                return false // short press: normal OK
            }
            if (consumed.contains(keyCode)) return true
            if (!(event.isLongPress || event.repeatCount >= LONG_PRESS_REPEATS)) return false
            val handled = reloadStream(activity, playerFragment) ||
                (canRefreshScreen(activity, playerFragment) && RefreshButton.refreshCurrentScreen(activity))
            if (handled) {
                consumed.add(keyCode)
                activity.currentFocus?.let {
                    it.cancelLongPress()
                    it.isPressed = false // its key up is swallowed
                }
                return true
            }
            return false
        }

        if (event.repeatCount > 0) return consumed.contains(keyCode) // holding = a single press

        if (scrubbing) {
            val current = scrub!!
            if (current.player.get() !== playerFragment || playerFragment?.isResumed != true) {
                endScrub(commit = false)
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1
                        moveScrub(activity, current, direction)
                        startHold(activity, keyCode, direction)
                    }
                    in okKeys -> endScrub(commit = true)
                    KeyEvent.KEYCODE_BACK -> {
                        // Not moved yet: hide the controls. Moved: cancel the move (controls stay).
                        val moved = current.position != current.start
                        endScrub(commit = false)
                        if (!moved) {
                            afterSeek = null
                            current.hideController()
                            backHidControls = true
                        }
                    }
                    // Up: let go (jumping there if the marker was moved); from there Android's normal
                    // navigation moves around the controls.
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val moved = current.position != current.start
                        endScrub(commit = moved)
                    }
                    // Down: ignored while the seek bar is held
                }
                consumed.add(keyCode)
                return true
            }
        }

        if (!activity.prefs().getBoolean(PREF_ENABLED, false)) return false
        val player = playerFragment as? PlayerFragment ?: return false
        if (!player.isAdded || !player.isResumed) return false
        val state = player.xtra5PlayerState()
        if (!state.isMaximized) return false
        if (activity.currentFocus is EditText) return false // typing in chat
        val controls = player.view?.findViewById<View>(R.id.playerControls)
        if (controls?.isVisible == true) {
            // Controls up because of a seek: Left / Right skip by the seek step.
            if (afterSeek?.get() === player && !state.isLiveStream) {
                val handled = when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        seek(activity, player, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                        state.showController() // keeps the controls up a little longer
                        true
                    }
                    else -> {
                        if (keyCode in dpadKeys || keyCode in okKeys) state.showController() // normal navigation
                        false
                    }
                }
                if (handled) consumed.add(keyCode)
                return handled
            }
            // Normal D-pad navigation of the controls; keep them up while the remote is in use.
            if (keyCode in dpadKeys || keyCode in okKeys) state.showController()
            return false
        }
        afterSeek = null

        val handled = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (state.isLiveStream) false else {
                    seek(activity, player, if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                    // Show the seek bar and time; Left / Right keep skipping while it's up.
                    state.showController()
                    watchAfterSeek(player)
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (state.isLiveStream || !startScrub(player, state)) state.showController()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                when {
                    state.hasChapters -> {
                        state.showChapters()
                        true
                    }
                    // Live stream: show / hide the chat beside the video.
                    state.isLiveStream && activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE -> state.toggleChat()
                    else -> false
                }
            }
            else -> false
        }
        if (handled) consumed.add(keyCode)
        return handled
    }

    /** True when there is no full-size video: no player, or the player is minimized. */
    private fun canRefreshScreen(activity: Activity, playerFragment: Fragment?): Boolean {
        if (!activity.prefs().getBoolean(PREF_ENABLED, false)) return false
        if (activity.currentFocus is EditText) return false // typing, e.g. in search
        val player = playerFragment as? PlayerFragment
        return player == null || !player.isAdded || !player.xtra5PlayerState().isMaximized
    }

    /** Restarts the player of a live stream (same as Restart player in the player menu). */
    private fun reloadStream(activity: Activity, playerFragment: Fragment?): Boolean {
        if (!activity.prefs().getBoolean(PREF_ENABLED, false)) return false
        val player = playerFragment as? PlayerFragment ?: return false
        if (!player.isAdded || !player.isResumed) return false
        val state = player.xtra5PlayerState()
        if (!state.isMaximized || !state.isLiveStream) return false
        if (activity.currentFocus is EditText) return false
        if (player.view?.findViewById<View>(R.id.playerControls)?.isVisible == true) return false
        player.restartPlayer()
        Toast.makeText(activity, "Reloading stream", Toast.LENGTH_SHORT).show()
        return true
    }

    private fun isMinimized(activity: Activity, playerFragment: Fragment?): Boolean {
        if (!activity.prefs().getBoolean(PREF_ENABLED, false)) return false
        val player = playerFragment as? PlayerFragment ?: return false
        return player.isAdded && !player.xtra5PlayerState().isMaximized
    }

    /** Closes the player the same way swiping it away does. */
    private fun closePlayer(activity: Activity, playerFragment: Fragment?): Boolean {
        if (!activity.prefs().getBoolean(PREF_ENABLED, false)) return false
        val player = playerFragment as? PlayerFragment ?: return false
        val main = activity as? MainActivity ?: return false
        if (!player.isAdded) return false
        player.close()
        main.closePlayer()
        return true
    }

    private fun seek(activity: Activity, player: PlayerFragment, direction: Int) {
        val position = player.getCurrentPosition() ?: return
        val stepMs = getSeekStep(activity) * 1000L
        var target = (position + direction * stepMs).coerceAtLeast(0L)
        player.getTotalDuration()?.takeIf { it > 0 }?.let { target = target.coerceAtMost(it) }
        player.seek(target)
        SeekIndicator.show(player, forward = direction > 0, seconds = getSeekStep(activity))
    }

    /** Shows the controls with the seek bar held at the current position. False if not possible. */
    private fun startScrub(player: PlayerFragment, state: PlayerState): Boolean {
        val view = player.view ?: return false
        val bar = view.findViewById<DefaultTimeBar>(R.id.progressBar) ?: return false
        val duration = player.getTotalDuration()?.takeIf { it > 0 } ?: return false
        val position = (player.getCurrentPosition() ?: return false).coerceIn(0L, duration)
        // A pressed seek bar stops the player from moving the marker and from auto-hiding the controls.
        bar.isPressed = true
        // Make the held marker obvious: gold, at its larger "dragging" size (shown while focused).
        bar.setScrubberColor(HELD_SCRUBBER)
        state.showController()
        val current = Scrub(WeakReference(player), state.showController, state.hideController, duration, position)
        scrub = current
        bar.requestFocusFromTouch() // after scrub is set, so the focus listener doesn't start a second hold
        showScrubPosition(current)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, SCRUB_TIMEOUT_MS)
        return true
    }

    private fun showScrubPosition(current: Scrub) {
        val view = current.player.get()?.view ?: return
        view.findViewById<DefaultTimeBar>(R.id.progressBar)?.setPosition(current.position)
        view.findViewById<TextView>(R.id.position)?.text = DateUtils.formatElapsedTime(current.position / 1000)
    }

    /** Lets go of the seek bar: seeks to the marker when [commit], otherwise puts it back. */
    private fun endScrub(commit: Boolean) {
        val current = scrub ?: return
        scrub = null
        stopHold()
        handler.removeCallbacks(timeout)
        val player = current.player.get() ?: return
        val view = player.view ?: return
        val bar = view.findViewById<DefaultTimeBar>(R.id.progressBar)
        bar?.isPressed = false
        bar?.setScrubberColor(DefaultTimeBar.DEFAULT_SCRUBBER_COLOR)
        if (commit) {
            player.seek(current.position)
        } else {
            val position = player.getCurrentPosition() ?: 0L
            bar?.setPosition(position)
            view.findViewById<TextView>(R.id.position)?.text = DateUtils.formatElapsedTime(position / 1000)
        }
        current.showController() // restarts the normal auto-hide timer
        // Until the controls hide, Left / Right skip by the seek step (see handle()).
        watchAfterSeek(player)
    }

    /** Marks [player]'s controls as up because of a seek, until they hide. */
    private fun watchAfterSeek(player: PlayerFragment) {
        if (afterSeek?.get() === player) return
        afterSeek = WeakReference(player)
        player.view?.findViewById<View>(R.id.playerControls)?.let { controls ->
            controls.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                var seenVisible = controls.isVisible // the controls may still be fading in
                override fun onGlobalLayout() {
                    if (controls.isVisible) seenVisible = true
                    if (afterSeek?.get() !== player) {
                        controls.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    } else if (seenVisible && !controls.isVisible && scrub == null) {
                        controls.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        afterSeek = null
                    }
                }
            })
        }
    }

    // Holding Left / Right while the seek bar is held keeps moving it, one step at a time.
    private var holdKey = 0
    private var holdDirection = 0
    private var holdActivity: WeakReference<Activity>? = null
    private val holdStep = object : Runnable {
        override fun run() {
            val current = scrub
            val activity = holdActivity?.get()
            if (current == null || activity == null) {
                stopHold()
                return
            }
            moveScrub(activity, current, holdDirection) // also keeps the idle timeout from firing
            handler.postDelayed(this, HOLD_REPEAT_MS)
        }
    }

    private fun startHold(activity: Activity, keyCode: Int, direction: Int) {
        stopHold()
        holdKey = keyCode
        holdDirection = direction
        holdActivity = WeakReference(activity)
        handler.postDelayed(holdStep, HOLD_DELAY_MS)
    }

    private fun stopHold() {
        handler.removeCallbacks(holdStep)
        holdKey = 0
    }

    private fun moveScrub(activity: Activity, current: Scrub, direction: Int) {
        val step = current.duration * getScrubPercent(activity) / 100
        current.position = (current.position + direction * step).coerceIn(0L, current.duration)
        showScrubPosition(current)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, SCRUB_TIMEOUT_MS)
    }

    fun getScrubPercent(activity: Activity): Int =
        activity.prefs().getInt(PREF_SCRUB_PERCENT, DEFAULT_SCRUB_PERCENT).coerceIn(1, 10)

    fun getSeekStep(activity: Activity): Int =
        activity.prefs().getInt(PREF_SEEK_STEP, DEFAULT_SEEK_STEP).coerceIn(5, 60)
}
