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
        ChatInputExtras.attach(input, row, adapter) // red clear button, expanding input
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
}
