package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Xtra5s custom: Side-By-Side, two live streams at once, each with its own chat underneath.
 *
 * - [SideBySideFragment] is the "Side-By-Side" tab on the Following page (tab key [TAB_KEY]).
 * - [showAddDialog] is the "+" popup: a channel box, a quality dropdown (720p by default) and a
 *   mute button with a volume slider for each side. [showReplaceDialog] is the same for one side,
 *   to put another channel there.
 * - [SideBySideActivity] plays both streams.
 *
 * Quality and volume chosen here only apply to this mode; the normal player's settings are never
 * touched. Only the channel pairs are remembered (as "recent pairs" on the tab).
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: the Following tab list (C.DEFAULT_FOLLOWING_TABS, FollowPagerAdapter,
 * FollowPagerFragment, FollowMediaFragment, SettingsActivity tab names).
 */
object SideBySide {

    const val TAB_KEY = "4"
    const val TAB_TITLE = "Side-By-Side"

    const val SOURCE = "Source"
    const val DEFAULT_QUALITY = "720p"
    val QUALITIES = listOf(SOURCE, "1080p", "720p", "480p", "360p", "160p")

    private const val PREF_RECENT = "xtra5s_sbs_recent"
    private const val MAX_RECENT = 8

    /** What one side plays: the channel and this mode's own quality and volume. */
    data class Side(val login: String, val quality: String = DEFAULT_QUALITY, val volume: Int = 100, val muted: Boolean = false)

    /** The height a quality name asks for (Int.MAX_VALUE for Source). */
    fun targetHeight(quality: String): Int = when (quality) {
        SOURCE -> Int.MAX_VALUE
        else -> quality.removeSuffix("p").toIntOrNull() ?: 720
    }

    /** Turns "@Name", "twitch.tv/name" or a full channel link into a plain login. */
    fun cleanLogin(input: String): String {
        var text = input.trim()
        text = text.substringAfter("twitch.tv/", text)
        text = text.removePrefix("@").substringBefore('/').substringBefore('?')
        return text.lowercase().filter { it.isLetterOrDigit() || it == '_' }
    }

    // ---- Recent pairs ----

    fun recentPairs(context: Context): List<Pair<String, String>> =
        context.prefs().getString(PREF_RECENT, null).orEmpty().lines().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
        }

    fun addRecent(context: Context, left: String, right: String) {
        val list = recentPairs(context).filterNot { it.first == left && it.second == right }.toMutableList()
        list.add(0, left to right)
        context.prefs().edit { putString(PREF_RECENT, list.take(MAX_RECENT).joinToString("\n") { "${it.first},${it.second}" }) }
    }

    fun removeRecent(context: Context, pair: Pair<String, String>) {
        val list = recentPairs(context).filterNot { it == pair }
        context.prefs().edit { putString(PREF_RECENT, list.joinToString("\n") { "${it.first},${it.second}" }) }
    }

    // ---- Launching ----

    fun start(activity: FragmentActivity, left: Side, right: Side) {
        addRecent(activity, left.login, right.login)
        // Don't play a third stream underneath: close the normal player first.
        (activity as? MainActivity)?.let { main ->
            (main.playerFragment as? PlayerFragment)?.let { player ->
                // Same as the player's own close: stop it and remove it from the screen.
                try {
                    player.minimize()
                    player.close()
                    main.closePlayer()
                } catch (_: Exception) {}
            }
        }
        activity.startActivity(Intent(activity, SideBySideActivity::class.java).apply {
            putSide(this, SideBySideActivity.LEFT, left)
            putSide(this, SideBySideActivity.RIGHT, right)
        })
    }

    private fun putSide(intent: Intent, prefix: String, side: Side) {
        intent.putExtra("${prefix}_login", side.login)
        intent.putExtra("${prefix}_quality", side.quality)
        intent.putExtra("${prefix}_volume", side.volume)
        intent.putExtra("${prefix}_muted", side.muted)
    }

    fun readSide(intent: Intent, prefix: String): Side? {
        val login = intent.getStringExtra("${prefix}_login")?.takeIf { it.isNotBlank() } ?: return null
        return Side(
            login = login,
            quality = intent.getStringExtra("${prefix}_quality")?.takeIf { it in QUALITIES } ?: DEFAULT_QUALITY,
            volume = intent.getIntExtra("${prefix}_volume", 100).coerceIn(0, 100),
            muted = intent.getBooleanExtra("${prefix}_muted", false),
        )
    }

    /** True when [context] belongs to the Side-By-Side screen. */
    fun isSideBySide(context: Context?): Boolean =
        context != null && generateSequence(context) { (it as? ContextWrapper)?.baseContext }.any { it is SideBySideActivity }

    // ---- Channel suggestions ----

    /** Channels to suggest while typing: recent ones, then followed ones (Twitch and local). */
    suspend fun suggestions(context: Context): List<String> = withContext(Dispatchers.IO) {
        val names = LinkedHashSet<String>()
        recentPairs(context).forEach { names.add(it.first); names.add(it.second) }
        val module = (context.applicationContext as XtraApp).xtraModule
        try {
            module.localChannelFollowsRepository.getAll().forEach { follow -> follow.userLogin?.let { names.add(it.lowercase()) } }
        } catch (_: Exception) {}
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (!headers[C.HEADER_TOKEN].isNullOrBlank()) {
            val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            var cursor: String? = null
            try {
                repeat(10) {
                    val follows = module.graphQLRepository.loadQueryUserFollowedUsers(networkLibrary, headers, 100, cursor).data?.user?.follows ?: return@withContext names.toList()
                    val edges = follows.edges.orEmpty()
                    edges.forEach { edge -> edge?.node?.login?.let { names.add(it.lowercase()) } }
                    cursor = edges.lastOrNull()?.cursor?.toString()
                    if (follows.pageInfo?.hasNextPage == false || cursor.isNullOrBlank()) return@withContext names.toList()
                }
            } catch (_: Exception) {}
        }
        names.toList()
    }

    // ---- The "+" popup ----

    private class SideInputs(
        val name: MaterialAutoCompleteTextView,
        val quality: Spinner,
        val mute: ImageButton,
        val volume: Slider,
    ) {
        var muted = false
    }

    /** The "+" popup: two channel boxes side by side, each with quality and volume. */
    fun showAddDialog(activity: FragmentActivity, prefill: Pair<String, String>? = null) {
        val context = material(activity)
        val dp = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val left = buildColumn(context, "Left", prefill?.first)
        val right = buildColumn(context, "Right", prefill?.second)
        row.addView(left.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * dp).toInt() })
        row.addView(right.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        val leftInputs = left.second
        val rightInputs = right.second
        leftInputs.name.imeOptions = EditorInfo.IME_ACTION_NEXT
        rightInputs.name.imeOptions = EditorInfo.IME_ACTION_DONE

        val dialog = activity.getAlertDialogBuilder()
            .setTitle(TAB_TITLE)
            .setView(row)
            .setPositiveButton("Watch", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun submit() {
            val l = cleanLogin(leftInputs.name.text.toString())
            val r = cleanLogin(rightInputs.name.text.toString())
            when {
                l.isBlank() -> { leftInputs.name.requestFocus(); Toast.makeText(context, "Type a channel on the left", Toast.LENGTH_SHORT).show() }
                r.isBlank() -> { rightInputs.name.requestFocus(); Toast.makeText(context, "Type a channel on the right", Toast.LENGTH_SHORT).show() }
                else -> {
                    dialog.dismiss()
                    start(activity, sideOf(l, leftInputs), sideOf(r, rightInputs))
                }
            }
        }
        rightInputs.name.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            if (prefill != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus() else leftInputs.name.requestFocus()
        }
        dialog.show()

        activity.lifecycleScope.launch {
            val names = suggestions(context)
            if (names.isEmpty() || !dialog.isShowing) return@launch
            listOf(leftInputs, rightInputs).forEach {
                it.name.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, names))
            }
        }
    }

    /**
     * Puts another channel on one side: the same channel box, quality and volume as the "+"
     * popup, for one side only, starting from what that side has now.
     */
    fun showReplaceDialog(activity: FragmentActivity, title: String, current: Side, onPicked: (Side) -> Unit) {
        val context = material(activity)
        val dp = context.resources.displayMetrics.density
        val (column, inputs) = buildColumn(context, title, null, current)
        val frame = android.widget.FrameLayout(context).apply {
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(column)
        }
        inputs.name.imeOptions = EditorInfo.IME_ACTION_DONE
        val dialog = activity.getAlertDialogBuilder()
            .setTitle("Replace ${current.login}")
            .setView(frame)
            .setPositiveButton("Watch", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fun submit() {
            val login = cleanLogin(inputs.name.text.toString())
            if (login.isBlank()) {
                inputs.name.requestFocus()
                Toast.makeText(context, "Type a channel", Toast.LENGTH_SHORT).show()
                return
            }
            dialog.dismiss()
            onPicked(sideOf(login, inputs))
        }
        inputs.name.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { submit() }
            inputs.name.requestFocus()
        }
        dialog.show()
        activity.lifecycleScope.launch {
            val names = suggestions(context)
            if (names.isEmpty() || !dialog.isShowing) return@launch
            inputs.name.setAdapter(ArrayAdapter(context, android.R.layout.simple_dropdown_item_1line, names))
        }
    }

    private fun sideOf(login: String, inputs: SideInputs) = Side(
        login = login,
        quality = QUALITIES.getOrNull(inputs.quality.selectedItemPosition) ?: DEFAULT_QUALITY,
        volume = inputs.volume.value.toInt(),
        muted = inputs.muted,
    )

    private fun buildColumn(context: Context, title: String, prefill: String?, start: Side? = null): Pair<View, SideInputs> {
        val dp = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(context).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, 0, 0, (4 * dp).toInt())
        })
        val layout = TextInputLayout(context).apply { hint = "Channel" }
        val name = MaterialAutoCompleteTextView(layout.context).apply {
            setSingleLine()
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            threshold = 1
            prefill?.let { setText(it) }
        }
        layout.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(TextView(context).apply {
            text = "Quality"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, (10 * dp).toInt(), 0, 0)
        })
        val quality = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, QUALITIES)
            setSelection(QUALITIES.indexOf(start?.quality ?: DEFAULT_QUALITY).coerceAtLeast(0))
        }
        column.addView(quality, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val volumeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (6 * dp).toInt(), 0, 0)
        }
        val mute = ImageButton(context).apply {
            setImageResource(R.drawable.xtra5s_volume_up)
            contentDescription = "Mute"
            background = selectableBackground(context)
            val pad = (8 * dp).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val volume = Slider(context).apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            value = (start?.volume ?: 100).toFloat()
            setLabelFormatter { "${it.toInt()}%" }
        }
        volumeRow.addView(mute, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        volumeRow.addView(volume, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(volumeRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val inputs = SideInputs(name, quality, mute, volume)
        inputs.muted = start?.muted ?: false
        fun refreshMute() {
            val off = inputs.muted || volume.value == 0f
            mute.setImageResource(if (off) R.drawable.xtra5s_volume_off else R.drawable.xtra5s_volume_up)
            mute.contentDescription = if (inputs.muted) "Unmute" else "Mute"
            volume.alpha = if (inputs.muted) 0.5f else 1f
        }
        mute.setOnClickListener { inputs.muted = !inputs.muted; refreshMute() }
        refreshMute()
        volume.addOnChangeListener { _, _, fromUser -> if (fromUser && inputs.muted) inputs.muted = false; refreshMute() }
        return column to inputs
    }

    /**
     * [context] if its theme is a Material one, else a Material wrapper around it: sliders, text
     * boxes and Material buttons need one, and the app can be set to its older (non-Material 3) look.
     */
    fun material(context: Context): Context {
        val value = TypedValue()
        val isMaterial = context.theme.resolveAttribute(com.google.android.material.R.attr.isMaterialTheme, value, true)
        if (isMaterial) return context
        val night = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return android.view.ContextThemeWrapper(context, if (night) com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar else com.google.android.material.R.style.Theme_Material3_Light_NoActionBar)
    }

    fun selectableBackground(context: Context) = TypedValue().let { value ->
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, value, true)
        context.getDrawable(value.resourceId)
    }

    /** White icon tint for buttons over the video. */
    val WHITE: ColorStateList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
    const val GOLD = 0xFFFFC107.toInt()
}
