package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import androidx.annotation.Keep
import androidx.core.content.edit
import androidx.preference.Preference
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.ChatMessage
import com.github.andreyasadchy.xtra.ui.view.CenteredImageSpan
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Xtra5 custom: special chat messages.
 *  - Custom background colors for subs/notices, first-time chatters, channel point rewards,
 *    mentions of you and bot messages (Settings > Chat > Special messages). Defaults are the
 *    official colors; bots get a new color.
 *  - Bot accounts: a built-in list of common bots plus your own additions (editable).
 *  - Reply lines start with a small reply icon instead of "Replying to".
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: ChatAdapterUtils.prepareChatMessage (after the background is chosen; the
 * "Replying to" text).
 */
object SpecialMessages {

    const val PREF_NOTICE = "xtra5_color_notice"
    const val PREF_FIRST = "xtra5_color_first"
    const val PREF_REWARD = "xtra5_color_reward"
    const val PREF_MENTION = "xtra5_color_mention"
    const val PREF_BOT = "xtra5_color_bot"
    const val PREF_BOTS = "xtra5_bot_accounts"
    private const val DEFAULT_BOT_COLOR = 0x80455A64.toInt()
    /** Xtra5s: messages with bits (cheers), from [PREF_CHEER_MIN] bits up. */
    const val PREF_CHEER = "xtra5_color_cheer"
    const val PREF_CHEER_MIN = "xtra5_cheer_min"
    private const val DEFAULT_CHEER_COLOR = 0x809146FF.toInt() // the bits gem's purple

    fun cheerMinimum(context: Context) = context.prefs().getInt(PREF_CHEER_MIN, 1).coerceAtLeast(1)

    /**
     * Bits in a message: Twitch's own count (live chat), else the cheer emotes in the text added
     * up (VOD replays have no count): any cheer emote of the channel, e.g. "Cheer100 Kappa500".
     */
    internal fun bitsIn(bits: Int?, message: String?, cheerNames: Collection<String>): Int {
        if (bits != null && bits > 0) return bits
        if (message.isNullOrEmpty() || cheerNames.isEmpty()) return 0
        val names = cheerNames.map { it.lowercase() }.toSet()
        var total = 0
        message.split(' ', '\n', '\t').forEach { word ->
            val digits = word.takeLastWhile { it.isDigit() }
            if (digits.isEmpty() || digits.length > 7 || digits.length == word.length) return@forEach
            if (word.dropLast(digits.length).lowercase() in names) total += digits.toInt()
        }
        return total
    }

    val builtInBots = listOf(
        "nightbot", "streamelements", "moobot", "fossabot", "streamlabs", "wizebot", "sery_bot",
        "soundalerts", "pokemoncommunitygame", "botrixoficial", "kofistreambot", "blerp", "own3d",
        "commanderroot", "lolrankbot", "deepbot", "coebot", "phantombot", "streamcaptainbot",
    )

    fun defaultColor(context: Context, key: String): Int = when (key) {
        PREF_NOTICE -> context.getColor(R.color.chatMessageNotice)
        PREF_FIRST -> context.getColor(R.color.chatMessageFirst)
        PREF_REWARD -> context.getColor(R.color.chatMessageReward)
        PREF_MENTION -> context.getColor(R.color.chatMessageMention)
        PREF_CHEER -> DEFAULT_CHEER_COLOR
        else -> DEFAULT_BOT_COLOR
    }

    fun color(context: Context, key: String): Int =
        UserHighlights.parseColor(context.prefs().getString(key, null)) ?: defaultColor(context, key)

    fun setColor(context: Context, key: String, color: Int) {
        context.prefs().edit { putString(key, UserHighlights.toHex(color)) }
    }

    fun bots(context: Context): List<String> =
        context.prefs().getString(PREF_BOTS, null)?.lines()?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: builtInBots

    private var cachedBotsSource: String? = "unset"
    private var cachedBots: Set<String> = emptySet()

    private fun isBot(context: Context, login: String?): Boolean {
        if (login == null) return false
        val source = context.prefs().getString(PREF_BOTS, null)
        if (source != cachedBotsSource) {
            cachedBotsSource = source
            cachedBots = bots(context).toSet()
        }
        return login.lowercase() in cachedBots
    }

    /**
     * Called right after official Xtra picks a message's background: applies the custom colors.
     * Order as official: first message, reward, (Xtra5s: cheers), notice, mention; then the
     * user's own highlight; then bots.
     */
    fun applyColors(context: Context, itemView: View, chatMessage: ChatMessage, wasMentioned: Boolean, firstMsgVisibility: Int, cheerEmotes: List<com.github.andreyasadchy.xtra.model.chat.CheerEmote> = emptyList()) {
        when {
            chatMessage.isFirst && firstMsgVisibility < 2 -> itemView.setBackgroundColor(color(context, PREF_FIRST))
            chatMessage.reward?.id != null && firstMsgVisibility < 2 -> itemView.setBackgroundColor(color(context, PREF_REWARD))
            bitsIn(chatMessage.bits, chatMessage.message, synchronized(cheerEmotes) { cheerEmotes.map { it.name } }) >= cheerMinimum(context) ->
                itemView.setBackgroundColor(color(context, PREF_CHEER))
            chatMessage.systemMsg != null || chatMessage.msgId != null -> itemView.setBackgroundColor(color(context, PREF_NOTICE))
            wasMentioned -> itemView.setBackgroundColor(color(context, PREF_MENTION))
            UserHighlights.colorFor(context, chatMessage) != null -> Unit // already highlighted
            isBot(context, chatMessage.userLogin) -> itemView.setBackgroundColor(color(context, PREF_BOT))
        }
    }

    /**
     * The grey quoted message of a reply, without "@name" mentions of the person replying (they
     * are the one writing the reply, so it only takes up room). Null if nothing is left.
     */
    fun withoutReplierMention(message: String?, replierLogin: String?, replierName: String?): String? {
        if (message == null) return null
        val names = listOfNotNull(replierLogin, replierName).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (names.isEmpty()) return message
        val pattern = Regex("(^|\\s)@(" + names.joinToString("|") { Regex.escape(it) } + ")(?=$|[\\s,.:;!?])", RegexOption.IGNORE_CASE)
        if (!pattern.containsMatchIn(message)) return message
        val trimmed = pattern.replace(message, "$1").replace(Regex(" {2,}"), " ").trim()
        return trimmed.ifEmpty { null }
    }

    /** "Replying to name: " with a small reply icon in place of "Replying to". */
    fun replyPrefix(context: Context, replyMessage: String, userName: String?): CharSequence {
        val full = replyMessage.format(userName, "")
        val before = replyMessage.substringBefore("%1\$s")
        if (before.isEmpty() || !full.startsWith(before)) return full
        val text = "\uFFFC " + full.removePrefix(before)
        val icon = context.getDrawable(R.drawable.xtra5_reply_right)?.mutate() ?: return full
        icon.setTint(Color.parseColor("#999999"))
        val prefs = context.prefs()
        val sp = (prefs.getString("chat_text_size", "14")?.toFloatOrNull() ?: 14f) * (prefs.getInt("chat_size_modifier", 100) / 100f)
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, context.resources.displayMetrics).toInt()
        icon.setBounds(0, 0, size, size)
        return SpannableString(text).apply { setSpan(CenteredImageSpan(icon), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** The "minimum bits" editor (Settings and the Chat colors panel). */
    fun showCheerMinimum(context: Context, onDone: () -> Unit) {
        val builder = context.getAlertDialogBuilder()
        val ctx = builder.context
        val density = ctx.resources.displayMetrics.density
        val input = EditText(ctx).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(cheerMinimum(ctx).toString())
            selectAll()
            isSingleLine = true
    }
    val frame = LinearLayout(ctx).apply {
        setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    builder.setTitle("Minimum bits")
        .setMessage("Cheers with at least this many bits get the cheer color (1 = every cheer; TTS cheers are often 200 or more).")
        .setView(frame)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val value = input.text.toString().trim().toIntOrNull()?.coerceIn(1, 1_000_000) ?: 1
            context.prefs().edit { putInt(PREF_CHEER_MIN, value) }
            onDone()
            ChatLive.refreshChat()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }

    fun cheerMinimumSummary(context: Context): String {
        val min = cheerMinimum(context)
        return if (min <= 1) "Every cheer" else "$min bits or more"
    }

    /** The bot accounts editor (Settings and the Chat colors panel). */
    fun showBotAccounts(context: Context, onDone: () -> Unit) {
        val builder = context.getAlertDialogBuilder()
        val ctx = builder.context
        val density = ctx.resources.displayMetrics.density
        val input = EditText(ctx).apply {
            setText(bots(ctx).joinToString("\n"))
            minLines = 6
            maxLines = 14
            isSingleLine = false
    }
    val frame = LinearLayout(ctx).apply {
        setPadding((24 * density).toInt(), (8 * density).toInt(), (24 * density).toInt(), 0)
        addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    builder.setTitle("Bot accounts (one per line)")
        .setView(frame)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val names = input.text.lines().map { it.trim().removePrefix("@") }.filter { it.isNotEmpty() }
            context.prefs().edit { putString(PREF_BOTS, names.joinToString("\n")) }
            onDone()
            ChatLive.refreshChat()
        }
        .setNeutralButton("Reset to built-in") { _, _ ->
            context.prefs().edit { remove(PREF_BOTS) }
            onDone()
            ChatLive.refreshChat()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }

    fun botAccountsSummary(context: Context) = "${bots(context).size} accounts"

}

/** A special message color in Settings: shows a swatch, opens the color picker. */
@Keep
class SpecialColorPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    override fun onAttached() {
        super.onAttached()
        update()
    }

    override fun onClick() {
        HighlightDialogs.showColorPicker(context, title.toString(), "chatter", SpecialMessages.color(context, key)) { color ->
            SpecialMessages.setColor(context, key, color)
            update()
            ChatLive.refreshChat()
        }
    }

    private fun update() {
        icon = HighlightDialogs.swatch(context, SpecialMessages.color(context, key), 28)
    }

    // Xtra's settings theme tints every icon grey; the swatch must show its real color.
    override fun onBindViewHolder(holder: androidx.preference.PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        HighlightDialogs.showSwatchColors(holder)
    }
}

/** Xtra5s: Settings > Chat > Special messages > minimum bits for the cheer color. */
@Keep
class CheerMinimumPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    override fun onAttached() {
        super.onAttached()
        update()
    }

    override fun onClick() {
        SpecialMessages.showCheerMinimum(context) { update() }
    }

    private fun update() {
        summary = SpecialMessages.cheerMinimumSummary(context)
    }
}

/** The bot accounts list in Settings: one name per line. */
@Keep
class BotAccountsPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    override fun onAttached() {
        super.onAttached()
        update()
    }

    override fun onClick() {
        SpecialMessages.showBotAccounts(context) { update() }
    }

    private fun update() {
        summary = SpecialMessages.botAccountsSummary(context)
    }
}
