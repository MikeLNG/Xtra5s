package com.github.andreyasadchy.xtra.custom

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.player.lowlatency.OkHttpDataSource
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.player.ExoPlayerService
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.applyTheme
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Xtra5s custom: Dual Stream (was Side-By-Side): two live streams on one screen.
 * Chats are hidden when it opens (two chats in one window don't draw properly: they flicker to
 * old messages); the chat button shows both, each stream's normal Xtra5 chat (highlights apply).
 *  - Chats hidden: the two videos side by side (landscape, TV) or stacked (portrait).
 *  - Chats shown, landscape: side by side, each video on top and its chat underneath.
 *  - Chats shown, portrait: both videos stacked on top, the two chats side by side underneath.
 *
 * Tap (or OK on) a video to show its controls: a big pause / play in the middle (playing again
 * jumps to live), and mute, volume, quality, chat, message box (while chats show; hidden by
 * default), chat commands (BotCommands), swap, replace (another channel on this side), reload, watch only this (in the
 * normal player) and fullscreen.
 * Swipe down on a video = replace it; swipe up = keep only that stream, in the normal player.
 * Touch and hold a video, then slide left / right: that video's volume (its width = 0-100%),
 * with a volume bar in the middle of it while adjusting.
 * Quality and volume here only apply to this screen.
 */
@OptIn(UnstableApi::class)
class SideBySideActivity : AppCompatActivity() {

    companion object {
        const val LEFT = "left"
        const val RIGHT = "right"
        private const val CONTROLS_TIMEOUT = 4000L
        private const val OFFLINE_RECHECK = 60_000L
    }

    /** [prefix] is the stream's position: LEFT = Stream 1 (left / top), RIGHT = Stream 2. */
    private inner class Pane(var prefix: String, val chatId: Int) {
        lateinit var side: SideBySide.Side
        var quality = SideBySide.DEFAULT_QUALITY
        var volume = 100
        var muted = false

        lateinit var videoFrame: FrameLayout
        lateinit var aspect: AspectRatioFrameLayout
        lateinit var surface: SurfaceView
        lateinit var label: TextView
        lateinit var status: TextView
        lateinit var controls: LinearLayout
        lateinit var mute: ImageButton
        lateinit var slider: Slider
        lateinit var qualitySpinner: Spinner
        lateinit var expand: ImageButton
        lateinit var swap: ImageButton
        lateinit var root: LinearLayout
        lateinit var chat: FrameLayout
        lateinit var input: ImageButton
        /** The found channel, for its chat: id, login, display name, stream id. */
        var chatArgs: Array<String?>? = null
        /** This side's chat input is shown (hidden by default). */
        var inputShown = false
        /** The chat turned its input on (logged in), so it can be shown. */
        var inputAvailable = false
        /** The message text when the input was hidden: new text (an emote put in) shows it again. */
        var textWhenHidden = ""

        var player: ExoPlayer? = null
        var job: Job? = null
        var hideJob: Job? = null
        var qualityApplied = false
        var errors = 0
        var playing = false
        /** The live stream on this side, for handing it to the normal player (null while offline). */
        var stream: Stream? = null
        lateinit var center: ImageButton
        lateinit var volumeBar: LinearLayout
        lateinit var volumeFill: View
        lateinit var volumeText: TextView
        lateinit var volumeIcon: android.widget.ImageView
        var volumeBarJob: Job? = null
        /** Paused by the user (the big button). Playing again jumps to live. */
        var paused = false

        fun showStatus(text: String?) {
            status.text = text
            status.isVisible = text != null
        }

        fun applyVolume() {
            // Ears hear loudness as roughly (signal level)^0.6, so a straight slider made 30%
            // sound about half as loud. Level = slider^(5/3) makes the number match what you
            // hear: 30% sounds about 30% as loud, 50% about half, 10% about a tenth.
            player?.volume = if (muted) 0f else Math.pow(volume / 100.0, 5.0 / 3.0).toFloat()
            mute.setImageResource(if (muted || volume == 0) R.drawable.xtra5s_volume_off else R.drawable.xtra5s_volume_up)
            mute.contentDescription = if (muted) "Unmute" else "Mute"
            slider.alpha = if (muted) 0.5f else 1f
        }
    }

    /** In screen order: Stream 1 first. Swapping the streams swaps them here. */
    private val panes = mutableListOf(Pane(LEFT, R.id.xtra5s_sbs_chat_left), Pane(RIGHT, R.id.xtra5s_sbs_chat_right))
    /** Both chats showing (hidden when Dual Stream opens). */
    private var chatShown = false
    private var expanded: Pane? = null
    private var stopped = false
    private lateinit var row: LinearLayout

    private val module get() = (application as XtraApp).xtraModule
    private val networkLibrary get() = prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        val left = SideBySide.readSide(intent, LEFT)
        val right = SideBySide.readSide(intent, RIGHT)
        if (left == null || right == null) {
            finish()
            return
        }
        panes[0].side = left
        panes[1].side = right
        // Chats that were showing before the app was in the background come back.
        chatShown = panes.any { supportFragmentManager.findFragmentById(it.chatId) != null }
        panes.forEach { pane ->
            pane.quality = savedInstanceState?.getString("${pane.prefix}_quality") ?: pane.side.quality
            pane.volume = savedInstanceState?.getInt("${pane.prefix}_volume", pane.side.volume) ?: pane.side.volume
            pane.muted = savedInstanceState?.getBoolean("${pane.prefix}_muted", pane.side.muted) ?: pane.side.muted
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        setContentView(buildLayout())
        panes.forEach { pane ->
            createPlayer(pane)
            pane.applyVolume()
            pane.inputShown = SideBySide.inputShown(this, pane.prefix)
            // The chat shows its input by itself (when logged in): keep it hidden unless turned on.
            pane.chat.viewTreeObserver.addOnGlobalLayoutListener { applyInput(pane) }
            load(pane)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val open = expanded
                if (open != null) setExpanded(open, false) else finish()
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        panes.forEach { pane ->
            outState.putString("${pane.prefix}_quality", pane.quality)
            outState.putInt("${pane.prefix}_volume", pane.volume)
            outState.putBoolean("${pane.prefix}_muted", pane.muted)
        }
    }

    // ---- Layout ----

    private fun buildLayout(): View {
        val dp = resources.displayMetrics.density
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
        }
        panes.forEach { buildPane(it) }
        arrange()
        // The screen: both streams, with the battery percentage over the top-right corner.
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        battery = BatteryIndicator.attachStandalone(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        return root
    }

    private var battery: BatteryIndicator.Standalone? = null
    /**
     * True for touches that start in the strip along the top edge (status bar height plus a
     * little): those swipes are Android's (status bar / notifications), not Replace or volume.
     */
    private fun inTopEdge(e: MotionEvent): Boolean {
        val dp = resources.displayMetrics.density
        val bar = ViewCompat.getRootWindowInsets(window.decorView)?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        return e.rawY < maxOf(bar, (24 * dp).toInt()) + 16 * dp
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(0xFF303030.toInt()) }

    private fun isPortrait() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    private fun detach(view: View) = (view.parent as? ViewGroup)?.removeView(view)

    /** Containers of the current arrangement, hidden in fullscreen. */
    private val arrangedExtras = ArrayList<View>()
    private var arrangedPortrait: Boolean? = null
    private var arrangedChat: Boolean? = null

    /**
     * Puts the videos (and the chats, when shown) in place for the orientation, when it changed,
     * the chats were shown / hidden or the streams were swapped, then applies fullscreen by
     * hiding everything but that video. Views are moved, not rebuilt, so the players keep going.
     */
    private fun arrange(force: Boolean = false) {
        val dp = resources.displayMetrics.density
        val line = (2 * dp).toInt()
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val portrait = isPortrait()
        val chats = chatShown
        panes.forEach { pane ->
            pane.swap.setImageResource(if (portrait) R.drawable.xtra5s_swap_vert else R.drawable.xtra5s_swap)
            pane.input.isVisible = chats
            pane.input.alpha = if (pane.inputShown) 1f else 0.6f
        }
        if (force || arrangedPortrait != portrait || arrangedChat != chats) {
            val moved = arrangedPortrait != null
            arrangedPortrait = portrait
            arrangedChat = chats
            arrangedExtras.clear()
            panes.forEach { pane ->
                detach(pane.videoFrame)
                detach(pane.chat)
                detach(pane.root)
                pane.root.removeAllViews()
            }
            row.removeAllViews()
            row.orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (!chats) {
                panes.forEachIndexed { index, pane ->
                    if (index == 1) {
                        row.addView(divider().also { arrangedExtras.add(it) },
                            if (portrait) LinearLayout.LayoutParams(match, line) else LinearLayout.LayoutParams(line, match))
                    }
                    row.addView(pane.videoFrame, if (portrait) LinearLayout.LayoutParams(match, 0, 1f) else LinearLayout.LayoutParams(0, match, 1f))
                }
            } else if (portrait) {
                row.addView(panes[0].videoFrame, LinearLayout.LayoutParams(match, wrap))
                val between = divider()
                row.addView(between, LinearLayout.LayoutParams(match, line))
                row.addView(panes[1].videoFrame, LinearLayout.LayoutParams(match, wrap))
                val chatRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                chatRow.addView(panes[0].chat, LinearLayout.LayoutParams(0, match, 1f))
                chatRow.addView(divider(), LinearLayout.LayoutParams(line, match))
                chatRow.addView(panes[1].chat, LinearLayout.LayoutParams(0, match, 1f))
                row.addView(chatRow, LinearLayout.LayoutParams(match, 0, 1f))
                arrangedExtras.add(between)
                arrangedExtras.add(chatRow)
            } else {
                panes.forEachIndexed { index, pane ->
                    if (index == 1) row.addView(divider().also { arrangedExtras.add(it) }, LinearLayout.LayoutParams(line, match))
                    pane.root.addView(pane.videoFrame, LinearLayout.LayoutParams(match, wrap))
                    pane.root.addView(pane.chat, LinearLayout.LayoutParams(match, 0, 1f))
                    row.addView(pane.root, LinearLayout.LayoutParams(0, match, 1f))
                }
            }
            // Rows moved with their chat re-load their emotes.
            if (moved && chats) row.post { ChatLive.refreshChat() }
        }
        val open = expanded
        arrangedExtras.forEach { it.isVisible = open == null }
        panes.forEach { pane ->
            val isExpanded = pane == open
            val hidden = open != null && !isExpanded
            if (chats && !portrait) {
                pane.root.isVisible = !hidden
                pane.chat.isVisible = !isExpanded
            }
            pane.videoFrame.isVisible = !hidden
            if (chats) {
                pane.videoFrame.layoutParams = LinearLayout.LayoutParams(match, if (isExpanded) 0 else wrap, if (isExpanded) 1f else 0f)
                pane.aspect.resizeMode = if (isExpanded) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                pane.aspect.layoutParams = FrameLayout.LayoutParams(match, if (isExpanded) match else wrap, Gravity.CENTER)
            } else {
                pane.aspect.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // the whole video, centred in its half
                pane.aspect.layoutParams = FrameLayout.LayoutParams(match, match, Gravity.CENTER)
            }
            pane.expand.setImageResource(if (isExpanded) R.drawable.xtra5s_fullscreen_exit else R.drawable.xtra5s_fullscreen)
            pane.expand.contentDescription = if (isExpanded) "Exit fullscreen" else "Fullscreen"
        }
    }

    // ---- Chats ----

    /** Shows or hides both chats. Hidden chats are closed (they come back fresh when shown). */
    private fun toggleChat() {
        chatShown = !chatShown
        if (!chatShown) {
            currentFocus?.let { focused ->
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(focused.windowToken, 0)
            }
            val transaction = supportFragmentManager.beginTransaction()
            panes.forEach { pane -> supportFragmentManager.findFragmentById(pane.chatId)?.let { transaction.remove(it) } }
            if (!supportFragmentManager.isStateSaved) transaction.commitNow()
            panes.forEach { it.inputAvailable = false }
        }
        arrange()
        if (chatShown) panes.forEach { addChat(it) }
    }

    /** Adds [pane]'s chat, once its channel is found (while chats are shown). */
    private fun addChat(pane: Pane) {
        val args = pane.chatArgs ?: return
        if (!chatShown || isFinishing || supportFragmentManager.isStateSaved) return
        if (supportFragmentManager.findFragmentById(pane.chatId) != null) return
        supportFragmentManager.beginTransaction()
            .replace(pane.chatId, ChatFragment.newInstance(args[0], args[1], args[2], args[3]))
            .commit()
    }

    private fun toggleInput(pane: Pane) {
        if (!pane.inputShown && !pane.inputAvailable) {
            Toast.makeText(this, "Log in to chat", Toast.LENGTH_SHORT).show()
            return
        }
        pane.inputShown = !pane.inputShown
        SideBySide.setInputShown(this, pane.prefix, pane.inputShown)
        pane.input.alpha = if (pane.inputShown) 1f else 0.6f
        applyInput(pane)
    }

    /** Shows or hides [pane]'s chat input. The chat turns it on by itself when logged in. */
    private fun applyInput(pane: Pane) {
        val messageView = pane.chat.findViewById<View>(R.id.messageView) ?: return
        // Long-pressing an emote puts it in the message: show the input then, as in the normal player.
        val text = messageView.findViewById<TextView>(R.id.editText)?.text?.toString().orEmpty()
        if (!pane.inputShown && pane.inputAvailable && !messageView.isVisible && text.isNotEmpty() && text != pane.textWhenHidden) {
            pane.inputShown = true
            SideBySide.setInputShown(this, pane.prefix, true)
            pane.input.alpha = 1f
            messageView.isVisible = true
            return
        }
        if (messageView.isVisible && !pane.inputShown) {
            pane.inputAvailable = true
            pane.textWhenHidden = text
            messageView.findFocus()?.let { focused ->
                focused.clearFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(focused.windowToken, 0)
            }
            messageView.isVisible = false
            (supportFragmentManager.findFragmentById(pane.chatId) as? ChatFragment)?.let { chat ->
                if (chat.emoteMenuIsVisible()) chat.toggleEmoteMenu(false)
            }
        } else if (messageView.isVisible) {
            pane.inputAvailable = true
        } else if (pane.inputShown && pane.inputAvailable) {
            messageView.isVisible = true
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        arrange()
    }

    private fun buildPane(pane: Pane) {
        val dp = resources.displayMetrics.density
        pane.root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pane.chat = FrameLayout(this).apply {
            id = pane.chatId
            // Chat uses the app theme's background, as in the normal player.
            TypedValue().also { theme.resolveAttribute(android.R.attr.colorBackground, it, true) }.let { setBackgroundColor(it.data) }
        }

        pane.videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isClickable = true
            foreground = SideBySide.selectableBackground(context)
            setOnClickListener { toggleControls(pane) }
            // Hold-and-slide volume: set by onLongPress, then driven by the touch listener below.
            var adjusting = false
            var startX = 0f
            var startVolume = 0
            val swipes = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: MotionEvent) {
                    if (inTopEdge(e)) return
                    adjusting = true
                    startX = e.x
                    startVolume = if (pane.muted) 0 else pane.volume
                    // The video saw the finger go down: tell it the touch is over, so it drops its
                    // pressed (white) highlight and doesn't count it as a tap.
                    MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.let { cancel ->
                        this@apply.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    isPressed = false
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showVolumeBar(pane)
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    val start = e1 ?: return false
                    if (inTopEdge(start)) return false // pulling the status bar down, not Replace
                    val dy = e2.y - start.y
                    val dx = e2.x - start.x
                    if (kotlin.math.abs(dy) < 60 * dp || kotlin.math.abs(dy) < kotlin.math.abs(dx) * 1.5f) return false
                    if (dy > 0) replace(pane) else watchOnly(pane)
                    return true
                }
            })
            var swiped = false
            // Touches starting in the top-edge strip are the status bar's: the video doesn't see
            // them (no pressed highlight, no tap). A plain tap there (no swipe) still counts.
            var edgeTouch = false
            var edgeX = 0f
            var edgeY = 0f
            var edgeMoved = false
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    edgeTouch = inTopEdge(event)
                    edgeX = event.rawX
                    edgeY = event.rawY
                    edgeMoved = false
                }
                if (edgeTouch) {
                    val slop = android.view.ViewConfiguration.get(v.context).scaledTouchSlop
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(event.rawX - edgeX) > slop || kotlin.math.abs(event.rawY - edgeY) > slop) edgeMoved = true
                        MotionEvent.ACTION_UP -> {
                            edgeTouch = false
                            if (!edgeMoved) v.performClick()
                        }
                        MotionEvent.ACTION_CANCEL -> edgeTouch = false
                    }
                    return@setOnTouchListener true
                }
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { swiped = false; adjusting = false }
                if (adjusting) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            // Half the video's width = 0 to 100%.
                            val change = ((event.x - startX) / (v.width / 2f).coerceAtLeast(1f) * 100).toInt()
                            val volume = (startVolume + change).coerceIn(0, 100)
                            if (volume != pane.volume || (pane.muted && volume > 0)) {
                                pane.volume = volume
                                if (volume > 0) pane.muted = false
                                pane.slider.value = volume.toFloat()
                                pane.applyVolume()
                            }
                            showVolumeBar(pane)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            adjusting = false
                            v.isPressed = false
                            hideVolumeBar(pane)
                        }
                    }
                    // Holding: no tap, and no swipe up / down.
                    return@setOnTouchListener true
                }
                if (swipes.onTouchEvent(event)) swiped = true
                // A swipe is not a tap: don't toggle the controls for it.
                swiped && event.actionMasked == MotionEvent.ACTION_UP
            }
            setOnFocusChangeListener { _, hasFocus -> outline(pane, hasFocus) }
        }
        pane.aspect = AspectRatioFrameLayout(this).apply {
            setAspectRatio(16f / 9f)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT // the whole video, centred in its half
        }
        pane.surface = SurfaceView(this)
        pane.aspect.addView(pane.surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pane.videoFrame.addView(pane.aspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        pane.label = TextView(this).apply {
            text = pane.side.login
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            val pad = (6 * dp).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply { setColor(0x66000000); cornerRadius = 4 * dp }
        }
        pane.videoFrame.addView(pane.label, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            val m = (6 * dp).toInt()
            setMargins(m, m, m, m)
        })
        pane.status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            gravity = Gravity.CENTER
        }
        pane.videoFrame.addView(pane.status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        pane.center = ImageButton(this).apply {
            setImageResource(R.drawable.xtra5s_pause)
            imageTintList = SideBySide.WHITE
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val pad = (14 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x80000000.toInt()) }
            contentDescription = "Pause"
            isVisible = false
            setOnClickListener { togglePause(pane); keepControls(pane) }
            setOnFocusChangeListener { v, hasFocus -> (v as ImageButton).imageTintList = if (hasFocus) android.content.res.ColorStateList.valueOf(SideBySide.GOLD) else SideBySide.WHITE }
        }
        pane.videoFrame.addView(pane.center, FrameLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt(), Gravity.CENTER))
        pane.volumeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pad = (10 * dp).toInt()
            setPadding(pad, pad, (14 * dp).toInt(), pad)
            background = GradientDrawable().apply { setColor(0xB3000000.toInt()); cornerRadius = 24 * dp }
            isVisible = false
        }
        pane.volumeIcon = android.widget.ImageView(this).apply { imageTintList = SideBySide.WHITE }
        val track = FrameLayout(this).apply {
            background = GradientDrawable().apply { setColor(0x4DFFFFFF); cornerRadius = 3 * dp }
        }
        pane.volumeFill = View(this).apply {
            background = GradientDrawable().apply { setColor(SideBySide.GOLD); cornerRadius = 3 * dp }
        }
        track.addView(pane.volumeFill, FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        pane.volumeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minEms = 2
            gravity = Gravity.END
        }
        pane.volumeBar.addView(pane.volumeIcon, LinearLayout.LayoutParams((24 * dp).toInt(), (24 * dp).toInt()))
        pane.volumeBar.addView(track, LinearLayout.LayoutParams((140 * dp).toInt(), (6 * dp).toInt()).apply {
            marginStart = (10 * dp).toInt()
            marginEnd = (10 * dp).toInt()
        })
        pane.volumeBar.addView(pane.volumeText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pane.videoFrame.addView(pane.volumeBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        pane.videoFrame.addView(buildControls(pane), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))


    }

    private fun buildControls(pane: Pane): View {
        val dp = resources.displayMetrics.density
        val pad = (8 * dp).toInt()
        fun button(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
            setImageResource(icon)
            imageTintList = SideBySide.WHITE
            contentDescription = description
            background = SideBySide.selectableBackground(context)
            setPadding(pad, pad, pad, pad)
            setOnClickListener { onClick(); keepControls(pane) }
            setOnFocusChangeListener { v, hasFocus -> (v as ImageButton).imageTintList = if (hasFocus) android.content.res.ColorStateList.valueOf(SideBySide.GOLD) else SideBySide.WHITE }
        }
        pane.controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
            setPadding(pad / 2, pad * 2, pad / 2, 0)
            isVisible = false
        }
        pane.mute = button(R.drawable.xtra5s_volume_up, "Mute") {
            pane.muted = !pane.muted
            pane.applyVolume()
        }
        pane.slider = Slider(SideBySide.material(this)).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = pane.volume.toFloat()
            setLabelFormatter { "${it.toInt()}%" }
            addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    pane.volume = value.toInt()
                    pane.muted = false
                    pane.applyVolume()
                    keepControls(pane)
                }
            }
        }
        pane.qualitySpinner = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, SideBySide.QUALITIES) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE) }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(SideBySide.QUALITIES.indexOf(pane.quality).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val quality = SideBySide.QUALITIES[position]
                    if (quality != pane.quality) {
                        pane.quality = quality
                        applyQuality(pane)
                    }
                    keepControls(pane)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        val chat = button(R.drawable.xtra5s_chat, "Show / hide chats") { toggleChat() }
        val commands = button(R.drawable.xtra5s_commands, "Chat commands") {
            BotCommands.show(this, pane.side.login, pane.stream != null) { command -> BotCommands.insertInto(pane.chat, command) }
        }
        pane.input = button(R.drawable.xtra5s_keyboard, "Show / hide message box") { toggleInput(pane) }
        pane.swap = button(R.drawable.xtra5s_swap, "Swap the two streams") { swapStreams() }
        val replace = button(R.drawable.xtra5s_tv, "Replace this channel") { replace(pane) }
        val reload = button(R.drawable.xtra5s_refresh, "Reload") { pane.paused = false; load(pane) }
        val only = button(R.drawable.xtra5s_open, "Watch only this stream") { watchOnly(pane) }
        pane.expand = button(R.drawable.xtra5s_fullscreen, "Fullscreen") { setExpanded(pane, expanded != pane) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
        pane.controls.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        pane.controls.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val buttons = listOf(pane.qualitySpinner, chat, pane.input, commands, pane.swap, replace, reload, only, pane.expand)
        // One row when the video is wide enough, else volume on one row and the buttons below it.
        var twoRows: Boolean? = null
        fun place(narrow: Boolean) {
            if (twoRows == narrow) return
            twoRows = narrow
            val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
            top.removeAllViews()
            bottom.removeAllViews()
            top.addView(pane.mute, LinearLayout.LayoutParams(wrap, wrap))
            top.addView(pane.slider, LinearLayout.LayoutParams(0, wrap, 1f))
            buttons.forEach { (if (narrow) bottom else top).addView(it, LinearLayout.LayoutParams(wrap, wrap)) }
            bottom.isVisible = narrow
        }
        place(false)
        pane.videoFrame.addOnLayoutChangeListener { v, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val narrow = (right - left) < 440 * dp // phones: two rows; each half of a TV (~480dp) keeps one
                if (narrow != twoRows) v.post { place(narrow) }
            }
        }
        return pane.controls
    }

    private fun outline(pane: Pane, focused: Boolean) {
        pane.videoFrame.foreground = if (focused) {
            GradientDrawable().apply { setStroke((2 * resources.displayMetrics.density).toInt(), SideBySide.GOLD) }
        } else SideBySide.selectableBackground(this)
    }

    // ---- Controls ----

    private fun toggleControls(pane: Pane) {
        if (pane.controls.isVisible) hideControls(pane) else {
            pane.controls.isVisible = true
            updateCenter(pane)
            keepControls(pane)
        }
    }

    /** The volume bar in the middle of [pane]'s video, while its volume is being slid. */
    private fun showVolumeBar(pane: Pane) {
        pane.volumeBarJob?.cancel()
        val volume = if (pane.muted) 0 else pane.volume
        pane.volumeIcon.setImageResource(if (volume == 0) R.drawable.xtra5s_volume_off else R.drawable.xtra5s_volume_up)
        pane.volumeText.text = "$volume%"
        val trackWidth = (140 * resources.displayMetrics.density).toInt()
        pane.volumeFill.layoutParams = pane.volumeFill.layoutParams.apply { width = trackWidth * volume / 100 }
        pane.volumeBar.alpha = 1f
        pane.volumeBar.isVisible = true
        pane.center.isVisible = false
    }

    private fun hideVolumeBar(pane: Pane) {
        pane.volumeBarJob?.cancel()
        pane.volumeBarJob = lifecycleScope.launch {
            delay(1000)
            pane.volumeBar.animate().alpha(0f).setDuration(250).withEndAction {
                pane.volumeBar.isVisible = false
                updateCenter(pane)
            }.start()
        }
    }

    /** The big middle button: shown with the controls, and always while paused. */
    private fun updateCenter(pane: Pane) {
        pane.center.isVisible = pane.playing && (pane.paused || pane.controls.isVisible) && !pane.volumeBar.isVisible
        pane.center.setImageResource(if (pane.paused) R.drawable.xtra5s_play else R.drawable.xtra5s_pause)
        pane.center.contentDescription = if (pane.paused) "Play" else "Pause"
    }

    /** Pauses this side only; playing again jumps to live. */
    private fun togglePause(pane: Pane) {
        val player = pane.player ?: return
        if (pane.paused) {
            pane.paused = false
            if (player.playbackState == Player.STATE_IDLE) {
                load(pane)
            } else {
                player.seekToDefaultPosition()
                player.play()
            }
        } else {
            pane.paused = true
            player.pause()
        }
        updateCenter(pane)
    }

    private fun keepControls(pane: Pane) {
        pane.hideJob?.cancel()
        pane.hideJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT)
            hideControls(pane)
        }
    }

    private fun hideControls(pane: Pane) {
        pane.hideJob?.cancel()
        // Keep the controls while one of them is being used with the remote.
        if (pane.controls.hasFocus() || pane.center.hasFocus()) {
            keepControls(pane)
            return
        }
        pane.controls.isVisible = false
        updateCenter(pane)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        battery?.wake()
        panes.forEach { if (it.controls.isVisible && (it.controls.hasFocus() || it.center.hasFocus())) keepControls(it) }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) battery?.wake()
        if (ev.action == MotionEvent.ACTION_DOWN) panes.forEach { if (it.controls.isVisible) keepControls(it) }
        return super.dispatchTouchEvent(ev)
    }

    private fun setExpanded(pane: Pane, expand: Boolean) {
        expanded = if (expand) pane else null
        arrange()
    }

    // ---- Playback ----

    private fun createPlayer(pane: Pane) {
        val player = ExoPlayer.Builder(this).apply {
            setLoadControl(
                DefaultLoadControl.Builder().apply {
                    setBufferDurationsMs(
                        prefs().getString(C.PLAYER_BUFFER_MIN, "15000")?.toIntOrNull() ?: 15000,
                        prefs().getString(C.PLAYER_BUFFER_MAX, "50000")?.toIntOrNull() ?: 50000,
                        prefs().getString(C.PLAYER_BUFFER_PLAYBACK, "2000")?.toIntOrNull() ?: 2000,
                        prefs().getString(C.PLAYER_BUFFER_REBUFFER, "2000")?.toIntOrNull() ?: 2000
                    )
                }.build()
            )
            // Both players play together, so neither may take audio focus from the other.
            setAudioAttributes(AudioAttributes.DEFAULT, false)
        }.build()
        player.setVideoSurfaceView(pane.surface)
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                if (!pane.qualityApplied && tracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }) {
                    pane.qualityApplied = true
                    applyQuality(pane)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        pane.errors = 0
                        pane.showStatus(null)
                    }
                    Player.STATE_ENDED -> {
                        pane.showStatus("Stream ended")
                        if (reconnect()) load(pane, after = 5000)
                    }
                    else -> {}
                }
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    pane.aspect.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                pane.errors++
                if (!reconnect()) {
                    pane.showStatus("Stream dropped")
                    return
                }
                pane.showStatus("Reconnecting…")
                load(pane, after = (2000L * pane.errors).coerceAtMost(15000))
            }
        })
        pane.player = player
    }

    /** Reconnecting dropped / failed streams by themselves (Settings > UI > Automatic refresh). */
    private fun reconnect() = AutoRefreshSettings.enabled(this, AutoRefreshSettings.DUAL_RECONNECT)

    /** Looks the channel up, adds its chat (when chats show), and plays the stream (or waits while it's offline). */
    private fun load(pane: Pane, after: Long = 0) {
        pane.job?.cancel()
        pane.job = lifecycleScope.launch {
            if (after > 0) delay(after)
            pane.playing = false
            updateCenter(pane)
            pane.qualityApplied = false
            if (pane.player?.isPlaying != true) pane.showStatus("Loading…")
            val user = try {
                module.graphQLRepository.loadQueryUsersStream(networkLibrary, TwitchApiHelper.getGQLHeaders(this@SideBySideActivity), logins = listOf(pane.side.login)).data?.users?.firstOrNull()
            } catch (e: Exception) {
                pane.showStatus("Couldn't load ${pane.side.login}")
                if (reconnect()) load(pane, after = 10000)
                return@launch
            }
            if (user == null) {
                pane.showStatus("Channel not found:\n${pane.side.login}")
                return@launch
            }
            pane.label.text = user.displayName ?: user.login ?: pane.side.login
            pane.chatArgs = arrayOf(user.id, user.login, user.displayName, user.stream?.id)
            addChat(pane)
            pane.stream = user.stream?.let { stream ->
                Stream(
                    id = stream.id,
                    channelId = user.id,
                    channelLogin = user.login,
                    channelName = user.displayName,
                    channelImageURL = user.profileImageURL,
                    gameId = stream.game?.id,
                    gameSlug = stream.game?.slug,
                    gameName = stream.game?.displayName,
                    title = stream.broadcaster?.broadcastSettings?.title,
                    thumbnailURL = stream.previewImageURL,
                    createdAt = stream.createdAt?.toString(),
                    viewerCount = stream.viewersCount,
                )
            }
            if (user.stream == null) {
                pane.player?.stop()
                pane.showStatus("Offline")
                if (AutoRefreshSettings.enabled(this@SideBySideActivity, AutoRefreshSettings.DUAL_OFFLINE)) load(pane, after = OFFLINE_RECHECK)
                return@launch
            }
            val url = try {
                module.playerRepository.loadStreamPlaylistUrl(
                    context = this@SideBySideActivity,
                    networkLibrary = networkLibrary,
                    gqlHeaders = TwitchApiHelper.getGQLHeaders(this@SideBySideActivity, prefs().getBoolean(C.TOKEN_INCLUDE_TOKEN_STREAM, true)),
                    channelLogin = user.login ?: pane.side.login,
                    platform = prefs().getString(C.TOKEN_PLATFORM, "web"),
                    playerType = prefs().getString(C.TOKEN_PLAYER_TYPE, "site"),
                    supportedCodecs = prefs().getString(C.TOKEN_SUPPORTED_CODECS, "av1,h265,h264"),
                    proxyPlaybackAccessToken = false,
                    proxyHost = null,
                    proxyPort = null,
                    proxyUser = null,
                    proxyPassword = null,
                    proxyTimeout = null,
                    enableIntegrity = false,
                )
            } catch (e: Exception) {
                pane.showStatus("Couldn't load the stream")
                if (reconnect()) load(pane, after = 10000)
                return@launch
            }
            play(pane, url)
        }
    }

    private fun play(pane: Pane, url: String) {
        val player = pane.player ?: return
        val source = HlsMediaSource.Factory(
            DefaultDataSource.Factory(this, OkHttpDataSource.Factory(module.okHttpClient.value, null, null, null, null) { false })
        ).apply {
            setPlaylistParserFactory(ExoPlayerService.CustomHlsPlaylistParserFactory())
            setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
        }.createMediaSource(
            MediaItem.Builder()
                .setUri(url.toUri())
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().apply {
                    prefs().getString(C.PLAYER_LIVE_TARGET_OFFSET, "2000")?.toLongOrNull()?.let { setTargetOffsetMs(it) }
                }.build())
                .build()
        )
        // Start near the chosen quality straight away; the exact track is picked once it's known.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_VIDEO)
            setMaxVideoSize(Int.MAX_VALUE, SideBySide.targetHeight(pane.quality))
        }.build()
        player.setMediaSource(source)
        player.prepare()
        player.playWhenReady = !stopped && !pane.paused
        pane.playing = true
        pane.applyVolume()
        updateCenter(pane)
    }

    /** Picks the stream's variant closest to (not above) the chosen quality. */
    private fun applyQuality(pane: Pane) {
        val player = pane.player ?: return
        val height = SideBySide.targetHeight(pane.quality)
        val params = player.trackSelectionParameters.buildUpon()
        run {
            params.setMaxVideoSize(Int.MAX_VALUE, height)
            val group = player.currentTracks.groups.firstOrNull { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
            if (group != null) {
                val formats = (0 until group.length).filter { group.isTrackSupported(it) }.map { it to group.getTrackFormat(it) }
                val best = formats.filter { it.second.height in 1..height }
                    .maxWithOrNull(compareBy({ it.second.height }, { it.second.frameRate }, { it.second.bitrate }))
                    ?: formats.minByOrNull { it.second.height.takeIf { h -> h > 0 } ?: Int.MAX_VALUE }
                if (best != null) {
                    params.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, best.first))
                }
            }
        }
        player.trackSelectionParameters = params.build()
    }

    // ---- Replace / watch only ----

    /** Puts another channel on [pane]'s side (popup with channel, quality and volume). */
    private fun replace(pane: Pane) {
        val current = SideBySide.Side(pane.side.login, pane.quality, pane.volume, pane.muted)
        SideBySide.showReplaceDialog(this, panes.indexOf(pane) + 1, current) { side ->
            pane.job?.cancel()
            pane.player?.stop()
            pane.side = side
            pane.quality = side.quality
            pane.volume = side.volume
            pane.muted = side.muted
            pane.stream = null
            pane.paused = false
            pane.inputAvailable = false
            pane.chatArgs = null
            pane.errors = 0
            pane.label.text = side.login
            pane.slider.value = side.volume.toFloat()
            pane.qualitySpinner.setSelection(SideBySide.QUALITIES.indexOf(side.quality).coerceAtLeast(0), false)
            pane.applyVolume()
            // The old channel's chat goes; the new one's is added once the channel is found.
            supportFragmentManager.findFragmentById(pane.chatId)?.let {
                if (!supportFragmentManager.isStateSaved) supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
            SideBySide.addRecent(this, panes[0].side.login, panes[1].side.login)
            SideBySide.writeSides(intent,
                panes[0].let { SideBySide.Side(it.side.login, it.quality, it.volume, it.muted) },
                panes[1].let { SideBySide.Side(it.side.login, it.quality, it.volume, it.muted) })
            load(pane)
        }
    }

    /**
     * Swaps the two streams' places (video, chat and their settings move together). Stream 1
     * is then the other channel; the recent pair and a restored screen follow the new order.
     */
    private fun swapStreams() {
        val first = panes[1]
        val second = panes[0]
        panes[0] = first
        panes[1] = second
        first.prefix = LEFT
        second.prefix = RIGHT
        fun current(pane: Pane) = SideBySide.Side(pane.side.login, pane.quality, pane.volume, pane.muted)
        SideBySide.writeSides(intent, current(first), current(second))
        SideBySide.addRecent(this, first.side.login, second.side.login)
        arrange(force = true)
    }

    /** Closes the other side and plays [pane]'s stream on its own, in the normal player. */
    private fun watchOnly(pane: Pane) {
        val stream = pane.stream
        if (stream == null) {
            Toast.makeText(this, "${pane.label.text} is offline", Toast.LENGTH_SHORT).show()
            return
        }
        panes.forEach { it.job?.cancel(); it.player?.stop() }
        startActivity(android.content.Intent(this, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = MainActivity.INTENT_LIVE_NOTIFICATION
            putExtra(MainActivity.KEY_VIDEO, stream)
        })
        finish()
    }

    override fun onStart() {
        super.onStart()
        if (stopped) {
            stopped = false
            // Back from the background: jump to live again.
            panes.forEach { load(it) }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            stopped = true
            panes.forEach { pane ->
                pane.job?.cancel()
                pane.player?.stop()
            }
        }
    }

    override fun onDestroy() {
        panes.forEach { pane ->
            pane.job?.cancel()
            pane.hideJob?.cancel()
            pane.volumeBarJob?.cancel()
            pane.player?.release()
            pane.player = null
        }
        super.onDestroy()
    }
}
