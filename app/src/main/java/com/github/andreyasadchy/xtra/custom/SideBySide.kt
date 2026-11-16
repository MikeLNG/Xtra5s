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
import android.widget.ImageView
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
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.target
import coil3.request.transformations
import coil3.transform.CircleCropTransformation
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Xtra5s custom: Dual Stream (was Side-By-Side), two live streams at once, each with its own chat.
 *
 * - [SideBySideFragment] is the "Dual Stream" tab on the Following page (tab key [TAB_KEY]).
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
    const val TAB_TITLE = "Dual Stream"

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

    /** The two channels, in either order: "a | b" and "b | a" are the same pair. */
    private fun samePair(x: Pair<String, String>, y: Pair<String, String>) =
        (x.first == y.first && x.second == y.second) || (x.first == y.second && x.second == y.first)

    /** Saved recent pairs (newest first); one entry per pair of channels, whichever way round. */
    fun parseRecent(source: String?): List<Pair<String, String>> {
        val result = ArrayList<Pair<String, String>>()
        source.orEmpty().lines().forEach { line ->
            val parts = line.split(',')
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return@forEach
            val pair = parts[0] to parts[1]
            if (result.none { samePair(it, pair) }) result.add(pair)
        }
        return result
    }

    /** [list] with first/second at the top, in that order, replacing the same pair either way round. */
    fun withRecent(list: List<Pair<String, String>>, first: String, second: String): List<Pair<String, String>> {
        val pair = first to second
        return (listOf(pair) + list.filterNot { samePair(it, pair) }).take(MAX_RECENT)
    }

    private fun serializeRecent(list: List<Pair<String, String>>) = list.joinToString("\n") { "${it.first},${it.second}" }

    fun recentPairs(context: Context): List<Pair<String, String>> = parseRecent(context.prefs().getString(PREF_RECENT, null))

    fun addRecent(context: Context, left: String, right: String) {
        context.prefs().edit { putString(PREF_RECENT, serializeRecent(withRecent(recentPairs(context), left, right))) }
    }

    fun removeRecent(context: Context, pair: Pair<String, String>) {
        val list = recentPairs(context).filterNot { samePair(it, pair) }
        context.prefs().edit { putString(PREF_RECENT, serializeRecent(list)) }
    }

    // ---- Chat input per side (hidden by default; only for Side-By-Side) ----

    fun inputShown(context: Context, side: String) = context.prefs().getBoolean("xtra5s_sbs_input_$side", false)
    fun setInputShown(context: Context, side: String, shown: Boolean) = context.prefs().edit { putBoolean("xtra5s_sbs_input_$side", shown) }

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

    /** Stores both sides on [intent] (Stream 1 first), e.g. after they were swapped. */
    fun writeSides(intent: Intent, first: Side, second: Side) {
        putSide(intent, SideBySideActivity.LEFT, first)
        putSide(intent, SideBySideActivity.RIGHT, second)
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
        val found = HashMap<String, String>()
        try {
            module.localChannelFollowsRepository.getAll().forEach { follow ->
                follow.userLogin?.lowercase()?.let { login ->
                    names.add(login)
                    follow.channelLogo?.takeIf { it.isNotBlank() }?.let { found[login] = it }
                }
            }
        } catch (_: Exception) {}
        try {
            followedNames(context, names, found)
        } finally {
            saveAvatars(context, found)
        }
        // Recent channels that aren't followed: look their pictures up once.
        ensureAvatars(context, recentPairs(context).flatMap { listOf(it.first, it.second) })
        names.toList()
    }

    private suspend fun followedNames(context: Context, names: LinkedHashSet<String>, found: HashMap<String, String>) {
        val module = (context.applicationContext as XtraApp).xtraModule
        val headers = TwitchApiHelper.getGQLHeaders(context, true)
        if (!headers[C.HEADER_TOKEN].isNullOrBlank()) {
            val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
            var cursor: String? = null
            try {
                repeat(10) {
                    val follows = module.graphQLRepository.loadQueryUserFollowedUsers(networkLibrary, headers, 100, cursor).data?.user?.follows ?: return
                    val edges = follows.edges.orEmpty()
                    edges.forEach { edge ->
                        edge?.node?.let { node ->
                            node.login?.lowercase()?.let { login ->
                                names.add(login)
                                TwitchApiHelper.getProfileImage(node.profileImageURL)?.let { found[login] = it }
                            }
                        }
                    }
                    cursor = edges.lastOrNull()?.cursor?.toString()
                    if (follows.pageInfo?.hasNextPage == false || cursor.isNullOrBlank()) return
                }
            } catch (_: Exception) {}
        }
    }

    // ---- Profile pictures (recent pairs and name suggestions) ----

    private const val PREF_AVATARS = "xtra5s_sbs_avatars"
    @Volatile private var avatarCache: MutableMap<String, String>? = null

    /** Saved profile picture links by login, so they show straight away. */
    fun avatars(context: Context): Map<String, String> = synchronized(this) {
        avatarCache ?: HashMap<String, String>().also { map ->
            try {
                val json = org.json.JSONObject(context.prefs().getString(PREF_AVATARS, null) ?: "{}")
                json.keys().forEach { key -> json.optString(key).takeIf { it.isNotBlank() }?.let { map[key] = it } }
            } catch (_: Exception) {}
            avatarCache = map
        }
    }

    private fun saveAvatars(context: Context, found: Map<String, String>) {
        if (found.isEmpty()) return
        synchronized(this) {
            val map = HashMap(avatars(context))
            if (found.all { map[it.key] == it.value }) return
            map.putAll(found)
            avatarCache = map
            context.prefs().edit { putString(PREF_AVATARS, org.json.JSONObject(map as Map<*, *>).toString()) }
        }
    }

    /** Looks up the profile pictures of [logins] that have none saved yet. */
    suspend fun ensureAvatars(context: Context, logins: List<String>) = withContext(Dispatchers.IO) {
        val missing = logins.distinct().filter { it.isNotBlank() && avatars(context)[it] == null }
        if (missing.isEmpty()) return@withContext
        val module = (context.applicationContext as XtraApp).xtraModule
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val found = HashMap<String, String>()
        missing.chunked(100).forEach { chunk ->
            try {
                module.graphQLRepository.loadQueryUsersStream(networkLibrary, TwitchApiHelper.getGQLHeaders(context), logins = chunk).data?.users?.forEach { user ->
                    val login = user?.login?.lowercase() ?: return@forEach
                    TwitchApiHelper.getProfileImage(user.profileImageURL)?.let { found[login] = it }
                }
            } catch (_: Exception) {}
        }
        saveAvatars(context, found)
    }

    /** Who was live at the last check (login -> live), shown on the recent pairs straight away. */
    private val liveNow = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun isLive(login: String): Boolean = liveNow[login] == true

    /**
     * Checks who of [logins] is live right now (one request per 100), saving any profile
     * pictures it gets too. Returns false if the check failed (the last known status stays).
     */
    suspend fun refreshLive(context: Context, logins: List<String>): Boolean = withContext(Dispatchers.IO) {
        val wanted = logins.distinct().filter { it.isNotBlank() }
        if (wanted.isEmpty()) return@withContext true
        val module = (context.applicationContext as XtraApp).xtraModule
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val avatarsFound = HashMap<String, String>()
        var ok = true
        wanted.chunked(100).forEach { chunk ->
            try {
                val users = module.graphQLRepository.loadQueryUsersStream(networkLibrary, TwitchApiHelper.getGQLHeaders(context), logins = chunk).data?.users
                if (users == null) {
                    ok = false
                    return@forEach
                }
                val live = HashSet<String>()
                users.forEach { user ->
                    val login = user?.login?.lowercase() ?: return@forEach
                    if (user.stream != null) live.add(login)
                    TwitchApiHelper.getProfileImage(user.profileImageURL)?.let { avatarsFound[login] = it }
                }
                chunk.forEach { liveNow[it] = it in live }
            } catch (_: Exception) {
                ok = false
            }
        }
        saveAvatars(context, avatarsFound)
        ok
    }

    /**
     * "LIVE" in red with a white outline, for the bottom-right corner of a profile picture.
     * Shown only while that channel is live.
     */
    class LiveBadge(context: Context) : TextView(context) {
        private val outline = (2.2f * context.resources.displayMetrics.density)

        init {
            text = "LIVE"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            includeFontPadding = false
            val pad = outline.toInt() + 1
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            // White outline first, then the red letters on top. Drawn straight from the text
            // layout (changing the text colour here would make the view redraw itself forever).
            val layout = layout ?: return
            val paint = paint
            canvas.save()
            canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = outline
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            paint.color = 0xFFFFFFFF.toInt()
            layout.draw(canvas)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = 0xFFE91916.toInt()
            layout.draw(canvas)
            canvas.restore()
        }
    }

    /** Shows [login]'s round profile picture in [view] (blank if not known yet). */
    fun loadAvatar(view: ImageView, login: String) {
        val url = avatars(view.context)[login]
        if (url == null) {
            view.setImageDrawable(null)
            return
        }
        view.context.imageLoader.enqueue(
            ImageRequest.Builder(view.context).apply {
                data(url)
                transformations(CircleCropTransformation())
                crossfade(true)
                target(view)
            }.build()
        )
    }

    /** Name suggestions with each channel's profile picture. */
    class SuggestionAdapter(context: Context, names: List<String>) : ArrayAdapter<String>(context, 0, names) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val dp = context.resources.displayMetrics.density
            val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pad = (12 * dp).toInt()
                setPadding(pad, (6 * dp).toInt(), pad, (6 * dp).toInt())
                addView(ImageView(context), LinearLayout.LayoutParams((40 * dp).toInt(), (40 * dp).toInt()))
                addView(TextView(context).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    setPadding((12 * dp).toInt(), 0, 0, 0)
                    setSingleLine()
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            val login = getItem(position).orEmpty()
            (row.getChildAt(1) as TextView).text = login
            loadAvatar(row.getChildAt(0) as ImageView, login)
            return row
        }
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
        // Side by side when each box has room for a full-length name (25 characters at this text
        // size, about 300dp); otherwise Left above Right, each the popup's full width.
        val popupWidthDp = minOf(context.resources.displayMetrics.widthPixels * 0.92f, 820 * dp) / dp
        val sideBySide = (popupWidthDp - 40 - 16) / 2 >= 300
        val row = LinearLayout(context).apply {
            orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), (8 * dp).toInt())
        }
        val left = buildColumn(context, 1, prefill?.first)
        val right = buildColumn(context, 2, prefill?.second)
        if (sideBySide) {
            row.addView(left.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * dp).toInt() })
            row.addView(right.first, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        } else {
            row.addView(left.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(right.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (20 * dp).toInt() })
        }
        val leftInputs = left.second
        val rightInputs = right.second
        leftInputs.name.imeOptions = EditorInfo.IME_ACTION_NEXT
        rightInputs.name.imeOptions = EditorInfo.IME_ACTION_DONE

        val dialog = activity.getAlertDialogBuilder()
            .setTitle(TAB_TITLE)
            .setView(android.widget.ScrollView(context).apply { addView(row) }) // stacked sides may need scrolling on short screens
            .setPositiveButton("Watch", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        fun submit() {
            val l = cleanLogin(leftInputs.name.text.toString())
            val r = cleanLogin(rightInputs.name.text.toString())
            when {
                l.isBlank() -> { leftInputs.name.requestFocus(); Toast.makeText(context, "Type a channel for Stream 1", Toast.LENGTH_SHORT).show() }
                r.isBlank() -> { rightInputs.name.requestFocus(); Toast.makeText(context, "Type a channel for Stream 2", Toast.LENGTH_SHORT).show() }
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
        widen(dialog, 820)

        activity.lifecycleScope.launch {
            val names = suggestions(context)
            if (names.isEmpty() || !dialog.isShowing) return@launch
            listOf(leftInputs, rightInputs).forEach {
                it.name.setAdapter(SuggestionAdapter(context, names))
            }
        }
    }

    /**
     * Puts another channel on one side: the same channel box, quality and volume as the "+"
     * popup, for one side only, starting from what that side has now.
     */
    /** [stream] is 1 or 2: where the side being replaced is (first / second). */
    fun showReplaceDialog(activity: FragmentActivity, stream: Int, current: Side, onPicked: (Side) -> Unit) {
        val context = material(activity)
        val dp = context.resources.displayMetrics.density
        val (column, inputs) = buildColumn(context, stream, null, current)
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
        widen(dialog, 480)
        activity.lifecycleScope.launch {
            val names = suggestions(context)
            if (names.isEmpty() || !dialog.isShowing) return@launch
            inputs.name.setAdapter(SuggestionAdapter(context, names))
        }
    }

    /** Popups get most of the screen width (up to [maxDp]), so the boxes aren't cramped. */
    private fun widen(dialog: AlertDialog, maxDp: Int) {
        val metrics = dialog.context.resources.displayMetrics
        val width = minOf((metrics.widthPixels * 0.92f).toInt(), (maxDp * metrics.density).toInt())
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun sideOf(login: String, inputs: SideInputs) = Side(
        login = login,
        quality = QUALITIES.getOrNull(inputs.quality.selectedItemPosition) ?: DEFAULT_QUALITY,
        volume = inputs.volume.value.toInt(),
        muted = inputs.muted,
    )

    /**
     * "Stream 1" / "Stream 2" with an arrow showing where that stream plays: left / right in
     * landscape (and on TV), up / down in portrait. Follows rotation while the popup is open.
     */
    private class StreamLabel(context: Context, private val stream: Int) : LinearLayout(context) {
        private val arrow = ImageView(context)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val dp = context.resources.displayMetrics.density
            addView(TextView(context).apply {
                text = "Stream $stream"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            })
            addView(arrow, LayoutParams((22 * dp).toInt(), (22 * dp).toInt()).apply { marginStart = (6 * dp).toInt() })
            update()
        }

        private fun update() {
            val portrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
            arrow.setImageResource(when {
                portrait && stream == 1 -> R.drawable.xtra5s_arrow_up
                portrait -> R.drawable.xtra5s_arrow_down
                stream == 1 -> R.drawable.xtra5s_arrow_left
                else -> R.drawable.xtra5s_arrow_right
            })
            arrow.contentDescription = if (portrait) (if (stream == 1) "top" else "bottom") else (if (stream == 1) "left" else "right")
        }

        override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
            super.onConfigurationChanged(newConfig)
            update()
        }
    }

    private fun buildColumn(context: Context, stream: Int, prefill: String?, start: Side? = null): Pair<View, SideInputs> {
        val dp = context.resources.displayMetrics.density
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(StreamLabel(context, stream).apply { setPadding(0, 0, 0, (6 * dp).toInt()) })
        val layout = TextInputLayout(context).apply { hint = "Channel" }
        val name = MaterialAutoCompleteTextView(layout.context).apply {
            setSingleLine()
            inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            threshold = 1
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            // Made in code, so it misses the box's usual inner padding (the text would start on
            // the box's left edge). The app uses the outlined box, whose "Channel" label sits on
            // the outline, so the text gets equal space above and below and is centred. The box
            // grows to fit; nothing is clipped.
            setPaddingRelative((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minHeight = (64 * dp).toInt()
            prefill?.let { setText(it) }
        }
        layout.addView(name, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(layout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(TextView(context).apply {
            text = "Quality"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, (14 * dp).toInt(), 0, 0)
        })
        val quality = Spinner(context).apply {
            adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_dropdown_item, QUALITIES) {
                private fun big(view: View) = (view as TextView).apply {
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    minHeight = (48 * dp).toInt()
                }
                override fun getView(position: Int, convertView: View?, parent: ViewGroup) = big(super.getView(position, convertView, parent))
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup) = big(super.getDropDownView(position, convertView, parent))
            }
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
    val WHITE: ColorStateList get() = ColorStateList.valueOf(0xFFFFFFFF.toInt())
    const val GOLD = 0xFFFFC107.toInt()
}
