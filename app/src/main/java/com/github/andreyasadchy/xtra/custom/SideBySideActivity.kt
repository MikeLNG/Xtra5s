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
 * Xtra5s custom: the Side-By-Side screen. Two live streams next to each other, each with its own
 * video on top and its own chat (the normal Xtra5 chat, so highlights and colors apply) underneath.
 *
 * Tap (or OK on) a video to show its controls: a big pause / play in the middle (playing again
 * jumps to live; the chat keeps going while paused), and mute, volume, quality, message box
 * (show / hide that side's chat input; hidden by default), replace (another channel on this
 * side), reload, watch only this (in the normal player) and fullscreen.
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

    private inner class Pane(val prefix: String, val chatId: Int) {
        lateinit var side: SideBySide.Side
        var quality = SideBySide.DEFAULT_QUALITY
        var volume = 100
        var muted = false

        lateinit var root: LinearLayout
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
        lateinit var chat: FrameLayout

        var player: ExoPlayer? = null
        var job: Job? = null
        var hideJob: Job? = null
        var qualityApplied = false
        var chatAdded = false
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
        /** This side's chat input is shown (Side-By-Side only setting, hidden by default). */
        var inputShown = false
        /** The chat turned its input on (logged in), so it can be shown. */
        var inputAvailable = false
        /** The message text when the input was hidden: new text (an emote put in) shows it again. */
        var textWhenHidden = ""

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

    private val panes = listOf(Pane(LEFT, R.id.xtra5s_sbs_chat_left), Pane(RIGHT, R.id.xtra5s_sbs_chat_right))
    private var expanded: Pane? = null
    private var stopped = false
    private lateinit var row: LinearLayout
    private lateinit var divider: View

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
            // A restored chat (after the app was in the background) is reused.
            pane.chatAdded = supportFragmentManager.findFragmentById(pane.chatId) != null
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
        divider = View(this).apply { setBackgroundColor(0xFF303030.toInt()) }
        panes.forEachIndexed { index, pane ->
            if (index == 1) row.addView(divider, LinearLayout.LayoutParams((2 * dp).toInt(), ViewGroup.LayoutParams.MATCH_PARENT))
            row.addView(buildPane(pane), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        ViewCompat.setOnApplyWindowInsetsListener(row) { view, windowInsets ->
            val bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        return row
    }

    private fun buildPane(pane: Pane): View {
        val dp = resources.displayMetrics.density
        pane.root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
                    adjusting = true
                    startX = e.x
                    startVolume = if (pane.muted) 0 else pane.volume
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    showVolumeBar(pane)
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    val start = e1 ?: return false
                    val dy = e2.y - start.y
                    val dx = e2.x - start.x
                    if (kotlin.math.abs(dy) < 60 * dp || kotlin.math.abs(dy) < kotlin.math.abs(dx) * 1.5f) return false
                    if (dy > 0) replace(pane) else watchOnly(pane)
                    return true
                }
            })
            var swiped = false
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) { swiped = false; adjusting = false }
                if (adjusting) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_MOVE -> {
                            val change = ((event.x - startX) / v.width.coerceAtLeast(1) * 100).toInt()
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
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        }
        pane.surface = SurfaceView(this)
        pane.aspect.addView(pane.surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pane.videoFrame.addView(pane.aspect, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

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
        pane.root.addView(pane.videoFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        pane.chat = FrameLayout(this).apply {
            id = pane.chatId
            // Chat uses the app theme's background, as in the normal player.
            TypedValue().also { theme.resolveAttribute(android.R.attr.colorBackground, it, true) }.let { setBackgroundColor(it.data) }
        }
        pane.root.addView(pane.chat, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return pane.root
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
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
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
        val input = button(R.drawable.xtra5s_keyboard, "Show / hide message box") { toggleInput(pane) }
        val replace = button(R.drawable.xtra5s_swap, "Replace this channel") { replace(pane) }
        val reload = button(R.drawable.xtra5s_refresh, "Reload") { pane.paused = false; load(pane) }
        val only = button(R.drawable.xtra5s_open, "Watch only this stream") { watchOnly(pane) }
        pane.expand = button(R.drawable.xtra5s_fullscreen, "Fullscreen") { setExpanded(pane, expanded != pane) }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        pane.controls.addView(pane.mute, LinearLayout.LayoutParams(wrap, wrap))
        pane.controls.addView(pane.slider, LinearLayout.LayoutParams(0, wrap, 1f))
        pane.controls.addView(pane.qualitySpinner, LinearLayout.LayoutParams(wrap, wrap))
        pane.controls.addView(input, LinearLayout.LayoutParams(wrap, wrap))
        pane.controls.addView(replace, LinearLayout.LayoutParams(wrap, wrap))
        pane.controls.addView(reload, LinearLayout.LayoutParams(wrap, wrap))
        pane.controls.addView(only, LinearLayout.LayoutParams(wrap, wrap))
        pane.controls.addView(pane.expand, LinearLayout.LayoutParams(wrap, wrap))
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

    /** Pauses this side only; playing again jumps to live. The chat keeps going either way. */
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

    // ---- Chat input per side ----

    private fun toggleInput(pane: Pane) {
        if (!pane.inputShown && !pane.inputAvailable) {
            Toast.makeText(this, "Log in to chat", Toast.LENGTH_SHORT).show()
            return
        }
        pane.inputShown = !pane.inputShown
        SideBySide.setInputShown(this, pane.prefix, pane.inputShown)
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
        panes.forEach { if (it.controls.isVisible && (it.controls.hasFocus() || it.center.hasFocus())) keepControls(it) }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) panes.forEach { if (it.controls.isVisible) keepControls(it) }
        return super.dispatchTouchEvent(ev)
    }

    private fun setExpanded(pane: Pane, expand: Boolean) {
        expanded = if (expand) pane else null
        panes.forEach { other ->
            val isExpanded = expand && other == pane
            other.root.isVisible = !expand || other == pane
            other.chat.isVisible = !isExpanded
            other.videoFrame.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isExpanded) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
                if (isExpanded) 1f else 0f,
            )
            other.aspect.resizeMode = if (isExpanded) AspectRatioFrameLayout.RESIZE_MODE_FIT else AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            other.aspect.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (isExpanded) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
            other.expand.setImageResource(if (isExpanded) R.drawable.xtra5s_fullscreen_exit else R.drawable.xtra5s_fullscreen)
            other.expand.contentDescription = if (isExpanded) "Exit fullscreen" else "Fullscreen"
        }
        divider.isVisible = !expand
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
                        load(pane, after = 5000)
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
                pane.showStatus("Reconnecting…")
                load(pane, after = (2000L * pane.errors).coerceAtMost(15000))
            }
        })
        pane.player = player
    }

    /** Looks the channel up, adds its chat, and plays the stream (or waits while it's offline). */
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
                load(pane, after = 10000)
                return@launch
            }
            if (user == null) {
                pane.showStatus("Channel not found:\n${pane.side.login}")
                return@launch
            }
            pane.label.text = user.displayName ?: user.login ?: pane.side.login
            if (!pane.chatAdded && !isFinishing && !supportFragmentManager.isStateSaved) {
                supportFragmentManager.beginTransaction()
                    .replace(pane.chatId, ChatFragment.newInstance(user.id, user.login, user.displayName, user.stream?.id))
                    .commit()
                pane.chatAdded = true
            }
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
                load(pane, after = OFFLINE_RECHECK)
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
                load(pane, after = 10000)
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
        SideBySide.showReplaceDialog(this, if (pane.prefix == LEFT) "Left" else "Right", current) { side ->
            pane.job?.cancel()
            pane.player?.stop()
            pane.side = side
            pane.quality = side.quality
            pane.volume = side.volume
            pane.muted = side.muted
            pane.stream = null
            pane.paused = false
            pane.inputAvailable = false
            pane.errors = 0
            pane.label.text = side.login
            pane.slider.value = side.volume.toFloat()
            pane.qualitySpinner.setSelection(SideBySide.QUALITIES.indexOf(side.quality).coerceAtLeast(0), false)
            pane.applyVolume()
            // The old channel's chat goes; the new one's is added once the channel is found.
            supportFragmentManager.findFragmentById(pane.chatId)?.let {
                supportFragmentManager.beginTransaction().remove(it).commitNow()
            }
            pane.chatAdded = false
            SideBySide.addRecent(this, panes[0].side.login, panes[1].side.login)
            load(pane)
        }
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
