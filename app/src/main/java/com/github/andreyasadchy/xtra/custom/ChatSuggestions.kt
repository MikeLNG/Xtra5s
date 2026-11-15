package com.github.andreyasadchy.xtra.custom

import android.database.DataSetObserver
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.MultiAutoCompleteTextView
import com.github.andreyasadchy.xtra.model.chat.Emote

/**
 * Xtra5 custom: chat input suggestions.
 *  - Emotes are suggested while typing normally (no ":" needed): from the 2nd letter of a word,
 *    emotes whose names start with it. ":" (looser matching) and "@" (users) work as before.
 *  - The suggestion popup always has the same shape: the full width of the input row, one row
 *    per suggestion up to 4 (then it scrolls), so it is never squeezed into a sliver.
 *  - Picking a suggestion in the middle of a sentence doesn't leave a double space.
 *  - Fullscreen (landscape, chat beside the video): a message too long for the input box is
 *    shown in full at the bottom-left of the video area while typing.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: ChatFragment (after the input's tokenizer is set) and the start of
 * AutoCompleteAdapter's filter.
 */
object ChatSuggestions {

    private const val MIN_LETTERS = 2
    private const val MAX_ROWS = 4

    /** Suggestions for a word typed without ":" or "@"; null means use Xtra's own matching. */
    fun <T> plainMatches(constraint: CharSequence, list: List<T?>): List<T?>? {
        if (constraint.startsWith(":") || constraint.startsWith("@")) return null
        if (constraint.length < MIN_LETTERS) return emptyList()
        val typed = constraint.toString()
        return list.filter { (it as? Emote)?.name?.startsWith(typed, ignoreCase = true) == true }
    }

    fun attach(input: MultiAutoCompleteTextView, row: View, adapter: ArrayAdapter<*>?) {
        adapter ?: return
        var rowHeight = 0
        fun resize() {
            val count = adapter.count
            if (count == 0) return
            if (rowHeight == 0) {
                val parent = FrameLayout(input.context)
                val item = adapter.getView(0, null, parent)
                item.layoutParams = item.layoutParams ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                item.measure(View.MeasureSpec.makeMeasureSpec(row.width.coerceAtLeast(1), View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
                rowHeight = item.measuredHeight.takeIf { it > 0 } ?: (52 * input.resources.displayMetrics.density).toInt()
            }
            if (row.width > 0) {
                input.dropDownWidth = row.width
                input.dropDownHorizontalOffset = -input.left // line up with the row's left edge
            }
            input.dropDownHeight = rowHeight * count.coerceAtMost(MAX_ROWS) + (8 * input.resources.displayMetrics.density).toInt()
        }
        adapter.registerDataSetObserver(object : DataSetObserver() {
            override fun onChanged() = resize()
        })
        // Long message in fullscreen: show all of it under the video.
        fun mirror() = updateMirror(input)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = mirror()
        })
        val previousFocus = input.onFocusChangeListener
        input.setOnFocusChangeListener { v, hasFocus ->
            previousFocus?.onFocusChange(v, hasFocus)
            mirror()
        }
        input.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) mirror() }
        // A picked suggestion ends with a space; drop it if a space already follows.
        input.addTextChangedListener(object : TextWatcher {
            private var removeAt = -1
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s ?: return
                val end = start + count
                removeAt = if (count > 1 && text[end - 1] == ' ' && end < text.length && text[end] == ' ') end else -1
            }

            override fun afterTextChanged(s: Editable?) {
                val at = removeAt
                removeAt = -1
                if (s == null || at < 0 || at >= s.length || s[at] != ' ') return
                s.delete(at, at + 1)
            }
        })
    }

    private fun updateMirror(input: MultiAutoCompleteTextView) {
        val activity = generateSequence(input.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstNotNullOfOrNull { it as? com.github.andreyasadchy.xtra.ui.main.MainActivity } ?: return
        val player = activity.playerFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment
        val root = player?.view
        val video = root?.findViewById<FrameLayout>(com.github.andreyasadchy.xtra.R.id.playerLayout)
        val existing = video?.findViewById<android.widget.TextView>(com.github.andreyasadchy.xtra.R.id.xtra5_input_mirror)
        val text = input.text?.toString().orEmpty()
        val visibleWidth = input.width - input.totalPaddingLeft - input.totalPaddingRight
        val show = video != null && player.isResumed &&
            input.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            player.xtra5PlayerState().isMaximized &&
            generateSequence(input as View) { it.parent as? View }.any { it === root } &&
            input.hasFocus() && text.isNotBlank() && visibleWidth > 0 && input.paint.measureText(text) > visibleWidth
        if (!show) {
            existing?.visibility = View.GONE
            return
        }
        val density = input.resources.displayMetrics.density
        val mirror = existing ?: android.widget.TextView(input.context).apply {
            id = com.github.andreyasadchy.xtra.R.id.xtra5_input_mirror
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 6 * density
                setColor(0xCC000000.toInt())
            }
            isClickable = false
            isFocusable = false
            video.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
                val margin = (12 * density).toInt()
                setMargins(margin, margin, margin, margin)
            })
        }
        mirror.maxWidth = (video.width - 24 * density).toInt().coerceAtLeast((100 * density).toInt())
        mirror.text = text
        mirror.visibility = View.VISIBLE
        mirror.bringToFront()
    }
}
