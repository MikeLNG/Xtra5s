package com.github.andreyasadchy.xtra.custom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Xtra5s custom: chat commands. The "!" button (player controls, and each Dual Stream video)
 * opens a table of the channel's chat-bot commands and their replies, from the bots' public
 * command lists: Nightbot, StreamElements and Fossabot (Moobot and custom bots have none).
 *  - Only commands everyone can use; switched-off, hidden and mod / sub-only ones are left out.
 *    Fossabot and StreamElements commands that only work live (or only offline) follow the
 *    stream's state.
 *  - Several bots on one channel: merged, one row per command name (Fossabot, then
 *    StreamElements, then Nightbot, whose lists can be old: it doesn't say if it's still used).
 *  - Search filters by command or reply; bot placeholders ($(user), [count]) are dimmed.
 *  - Tap a row: the command goes in the message box (or is copied, if chat isn't open).
 *    Long-press: copies the reply.
 * Emote names in replies show as emotes (BotEmotes). Lists are kept for 10 minutes per channel.
 */
object BotCommands {

    data class Command(val name: String, val aliases: List<String>, val reply: String, val bot: String)

    class Result(val commands: List<Command>, val bots: List<String>)

    const val NIGHTBOT = "Nightbot"
    const val STREAMELEMENTS = "StreamElements"
    const val FOSSABOT = "Fossabot"
    private const val CACHE_MS = 10 * 60_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = HashMap<String, Pair<Long, Result>>()

    // ---- Parsing (pure, tested) ----

    private fun JsonElement?.str(key: String) = (this as? JsonObject)?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
    private fun JsonElement?.bool(key: String) = (this as? JsonObject)?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull }
    private fun JsonElement?.int(key: String) = (this as? JsonObject)?.get(key)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull }
    private fun JsonElement?.list(key: String) = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()
    private fun JsonElement?.strings(key: String) = list(key).mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }

    internal fun withPrefix(name: String) = if (name.startsWith("!")) name else "!$name"

    /** Nightbot's /1/commands. Only "everyone" commands. Names are kept as they are. */
    internal fun parseNightbot(body: String): List<Command> {
        val root = json.parseToJsonElement(body)
        return root.list("commands").mapNotNull { c ->
            val name = c.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val reply = c.str("message")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if ((c.str("userLevel") ?: "everyone") != "everyone") return@mapNotNull null
            Command(name, emptyList(), reply, NIGHTBOT)
        }
    }

    /** StreamElements' public commands: switched on, not hidden, everyone (access level 100). */
    internal fun parseStreamElements(body: String, live: Boolean): List<Command> {
        val root = json.parseToJsonElement(body) as? JsonArray ?: return emptyList()
        return root.mapNotNull { c ->
            if (c.bool("enabled") == false || c.bool("hidden") == true) return@mapNotNull null
            if ((c.int("accessLevel") ?: 100) > 100) return@mapNotNull null
            if ((if (live) c.bool("enabledOnline") else c.bool("enabledOffline")) == false) return@mapNotNull null
            val name = c.str("command")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val reply = c.str("reply")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Command(withPrefix(name), c.strings("aliases").map { withPrefix(it.trim()) }, reply, STREAMELEMENTS)
        }
    }

    /** Fossabot's commands: no roles needed (everyone), on for the stream's state. */
    internal fun parseFossabot(body: String, live: Boolean): List<Command> {
        val root = json.parseToJsonElement(body)
        return root.list("commands").mapNotNull { c ->
            if (c.list("role_ids").isNotEmpty()) return@mapNotNull null
            if ((if (live) c.bool("enabled_online") else c.bool("enabled_offline")) == false) return@mapNotNull null
            val name = c.str("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val reply = c.str("response")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Command(withPrefix(name), c.strings("aliases").map { withPrefix(it.trim()) }, reply, FOSSABOT)
        }
    }

    /** One row per command name (earlier lists win), sorted A-Z. */
    internal fun merge(lists: List<List<Command>>): List<Command> {
        val seen = HashSet<String>()
        val out = ArrayList<Command>()
        lists.forEach { list ->
            list.forEach { command ->
                if (seen.add(command.name.lowercase())) out.add(command)
            }
        }
        return out.sortedWith(compareBy({ it.name.trimStart('!').lowercase() }, { it.name }))
    }

    internal fun search(list: List<Command>, query: String): List<Command> {
        val q = query.trim()
        if (q.isEmpty()) return list
        return list.filter { c ->
            c.name.contains(q, true) || c.reply.contains(q, true) || c.aliases.any { it.contains(q, true) }
        }
    }

    private val PLACEHOLDER = Regex("""\$\([^()]*(\([^()]*\)[^()]*)*\)|\$\{[^{}]*\}|\[[A-Za-z_][^\[\]]*(\[[^\[\]]*\][^\[\]]*)*\]""")

    /** Where the bot placeholders are in [text]: $(user), ${count}, [count], [twitch [channel]]. */
    internal fun placeholders(text: String): List<IntRange> = PLACEHOLDER.findAll(text).map { it.range }.toList()

    // ---- Loading ----

    private fun get(client: OkHttpClient, url: String, header: Pair<String, String>? = null): String? {
        val request = Request.Builder().url(url).apply { header?.let { header(it.first, it.second) } }.build()
        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body.string() else null
        }
    }

    private fun nightbot(client: OkHttpClient, login: String): List<Command>? {
        val channel = get(client, "https://api.nightbot.tv/1/channels/t/$login") ?: return null
        val id = json.parseToJsonElement(channel).jsonObject["channel"].str("_id") ?: return null
        return parseNightbot(get(client, "https://api.nightbot.tv/1/commands", "Nightbot-Channel" to id) ?: return null)
    }

    private fun streamElements(client: OkHttpClient, login: String, live: Boolean): List<Command>? {
        val channel = get(client, "https://api.streamelements.com/kappa/v2/channels/$login") ?: return null
        val id = json.parseToJsonElement(channel).str("_id") ?: return null
        return parseStreamElements(get(client, "https://api.streamelements.com/kappa/v2/bot/commands/$id/public") ?: return null, live)
    }

    private fun fossabot(client: OkHttpClient, login: String, live: Boolean): List<Command>? {
        val channel = get(client, "https://api.fossabot.com/v2/cached/channels/by-slug/$login") ?: return null
        val id = json.parseToJsonElement(channel).jsonObject["channel"].str("id") ?: return null
        return parseFossabot(get(client, "https://api.fossabot.com/v2/cached/channels/$id/commands") ?: return null, live)
    }

    suspend fun load(context: Context, login: String, live: Boolean): Result {
        val key = "${login.lowercase()}|$live"
        synchronized(cache) {
            cache[key]?.let { (time, result) -> if (System.currentTimeMillis() - time < CACHE_MS) return result }
        }
        val client = (context.applicationContext as XtraApp).xtraModule.okHttpClient.value
        val name = login.lowercase()
        val result = withContext(Dispatchers.IO) {
            coroutineScope {
                fun <T> safe(block: () -> T): T? = try { block() } catch (_: Exception) { null }
                val fossa = async { safe { fossabot(client, name, live) } }
                val se = async { safe { streamElements(client, name, live) } }
                val nb = async { safe { nightbot(client, name) } }
                val lists = listOf(FOSSABOT to fossa.await(), STREAMELEMENTS to se.await(), NIGHTBOT to nb.await())
                    .filter { !it.second.isNullOrEmpty() }
                Result(merge(lists.map { it.second!! }), lists.map { it.first })
            }
        }
        synchronized(cache) { cache[key] = System.currentTimeMillis() to result }
        return result
    }

    // ---- The table ----

    /**
     * Opens the commands table for [login]. [insert] puts a command in the chat's message box,
     * returning false if there is none (the command is copied instead).
     */
    fun show(activity: AppCompatActivity, login: String, live: Boolean, insert: (String) -> Boolean) {
        val context = SideBySide.material(activity)
        val dp = activity.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val primary = TypedValue().let { context.theme.resolveAttribute(android.R.attr.textColorPrimary, it, true); if (it.resourceId != 0) context.getColorStateList(it.resourceId).defaultColor else it.data }
        val dim = (primary and 0x00FFFFFF) or 0x80000000.toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(8), px(16), 0)
        }
        val searchBox = EditText(context).apply {
            hint = "Search commands or replies"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            isVisible = false
        }
        root.addView(searchBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val progress = ProgressBar(context)
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = px(24)
            bottomMargin = px(24)
        })
        val message = TextView(context).apply {
            gravity = Gravity.CENTER
            setPadding(0, px(24), 0, px(24))
            isVisible = false
        }
        root.addView(message, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
            isVisible = false
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val footer = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            alpha = 0.5f
            gravity = Gravity.END
            setPadding(0, px(4), 0, px(4))
            isVisible = false
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val dialog = context.getAlertDialogBuilder()
            .setTitle("Chat commands · $login")
            .setView(root)
            .setNegativeButton("Close", null)
            .create()

        fun copy(text: String, label: String) {
            (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
            Toast.makeText(activity, "Copied $label", Toast.LENGTH_SHORT).show()
        }

        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            var items: List<Command> = emptyList()
            var emotes: Map<String, com.github.andreyasadchy.xtra.model.chat.Emote> = emptyMap()
            override fun getItemCount() = items.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val row = LinearLayout(parent.context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    isFocusable = true
                    isClickable = true
                    background = TypedValue().let { tv -> parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); parent.context.getDrawable(tv.resourceId) }
                    setPadding(0, px(8), 0, px(8))
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                row.addView(TextView(parent.context).apply { setTypeface(typeface, Typeface.BOLD) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.35f).apply { marginEnd = px(12) })
                row.addView(TextView(parent.context), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.65f))
                return object : RecyclerView.ViewHolder(row) {}
            }
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val command = items[position]
                val row = holder.itemView as LinearLayout
                val name = row.getChildAt(0) as TextView
                val reply = row.getChildAt(1) as TextView
                name.text = if (command.aliases.isEmpty()) command.name else SpannableString(command.name + "\n" + command.aliases.joinToString(" ")).apply {
                    setSpan(android.text.style.RelativeSizeSpan(0.8f), command.name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.StyleSpan(Typeface.NORMAL), command.name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(dim), command.name.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val replyText = SpannableString(command.reply).apply {
                    placeholders(command.reply).forEach { setSpan(ForegroundColorSpan(dim), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                }
                reply.setTag(com.github.andreyasadchy.xtra.R.id.xtra5s_player_commands, null)
                reply.text = replyText
                BotEmotes.apply(reply, replyText, emotes)
                row.setOnClickListener {
                    if (insert(command.name)) dialog.dismiss() else copy(command.name, command.name)
                }
                row.setOnLongClickListener { copy(command.reply, "reply of ${command.name}"); true }
            }
        }
        list.adapter = adapter

        var all: List<Command> = emptyList()
        fun applySearch() {
            @Suppress("NotifyDataSetChanged")
            adapter.items = search(all, searchBox.text.toString())
            adapter.notifyDataSetChanged()
            message.isVisible = adapter.items.isEmpty()
            if (adapter.items.isEmpty()) message.text = if (all.isEmpty()) "No public bot commands found for this channel" else "No matching commands"
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applySearch()
        })

        dialog.show()
        // Big: most of the screen (over the video), not wider than a comfortable reading width.
        val metrics = activity.resources.displayMetrics
        dialog.window?.setLayout(minOf((metrics.widthPixels * 0.92f).toInt(), px(760)), (metrics.heightPixels * 0.88f).toInt())

        // Emotes load next to the commands; the table shows text until they're in.
        activity.lifecycleScope.launch {
            val emotes = try { BotEmotes.load(activity, login) } catch (_: Exception) { emptyMap() }
            if (!dialog.isShowing || emotes.isEmpty()) return@launch
            adapter.emotes = emotes
            @Suppress("NotifyDataSetChanged")
            adapter.notifyDataSetChanged()
        }
        activity.lifecycleScope.launch {
            val result = try { load(activity, login, live) } catch (_: Exception) { Result(emptyList(), emptyList()) }
            if (!dialog.isShowing) return@launch
            progress.isVisible = false
            all = result.commands
            searchBox.isVisible = all.isNotEmpty()
            list.isVisible = all.isNotEmpty()
            footer.isVisible = result.bots.isNotEmpty()
            footer.text = "From " + result.bots.joinToString(" · ")
            applySearch()
            if (all.isNotEmpty()) list.post { list.getChildAt(0)?.requestFocus() }
        }
    }

    /** Puts [command] in the message box inside [chat], if the chat's input is showing. */
    fun insertInto(chat: View?, command: String): Boolean {
        val input = chat?.findViewById<EditText>(com.github.andreyasadchy.xtra.R.id.editText) ?: return false
        if (!input.isShown) return false
        input.setText("$command ")
        input.setSelection(input.text.length)
        input.requestFocus()
        return true
    }
}
