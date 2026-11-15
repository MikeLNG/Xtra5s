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
 *  - Landscape, chat beside the video: a message too long for the input box floats in full over
 *    the video next to the input, growing as you type.
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
        fun mirror() = updateMirror(input, row)
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
        row.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (t != ot || b != ob) mirror() } // input moved
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

    /**
     * Landscape, chat beside the video: a message too long for the input box floats in full over
     * the video, right next to the input (bottom lined up with the input row). It is as wide as
     * the message, growing leftwards as you type, and wraps upwards at the video's left edge.
     */
    private fun updateMirror(input: MultiAutoCompleteTextView, row: View) {
        val activity = generateSequence(input.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .firstNotNullOfOrNull { it as? com.github.andreyasadchy.xtra.ui.main.MainActivity } ?: return
        val player = activity.playerFragment as? com.github.andreyasadchy.xtra.ui.player.PlayerFragment
        val root = player?.view
        val video = root?.findViewById<FrameLayout>(com.github.andreyasadchy.xtra.R.id.playerLayout)
        val existing = video?.findViewById<android.widget.TextView>(com.github.andreyasadchy.xtra.R.id.xtra5_input_mirror)
        val text = input.text?.toString().orEmpty()
        val visibleWidth = input.width - input.totalPaddingLeft - input.totalPaddingRight
        val show = video != null && player.isResumed && video.width > 0 &&
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
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(0xCC000000.toInt())
                setStroke((1 * density).toInt().coerceAtLeast(1), 0x99FFFFFF.toInt())
            }
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            video.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.BOTTOM or android.view.Gravity.END))
        }
        mirror.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, input.textSize)
        // Right edge just left of the chat, bottom lined up with the input row.
        val gap = (8 * density).toInt()
        val rowLoc = IntArray(2).also { row.getLocationInWindow(it) }
        val videoLoc = IntArray(2).also { video.getLocationInWindow(it) }
        val bottomGap = (videoLoc[1] + video.height - (rowLoc[1] + row.height)).coerceIn(gap, (video.height - gap * 4).coerceAtLeast(gap))
        (mirror.layoutParams as FrameLayout.LayoutParams).let { lp ->
            if (lp.bottomMargin != bottomGap || lp.marginEnd != gap || lp.marginStart != gap) {
                lp.bottomMargin = bottomGap
                lp.marginEnd = gap
                lp.marginStart = gap
                mirror.layoutParams = lp
            }
        }
        mirror.maxWidth = (video.width - 2 * gap).coerceAtLeast((100 * density).toInt())
        mirror.text = text
        mirror.visibility = View.VISIBLE
        mirror.bringToFront()
    }
}
