package com.github.andreyasadchy.xtra.custom

import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.TextView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.chat.ChatFragment
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment

/**
 * Xtra5 custom: chat input extras.
 *  - Red clear button: floats a little above the input's top-right corner whenever there is
 *    text; tapping it empties the input (no prompt), keeping the keyboard up.
 *  - Expanding input (landscape, chat beside the video): once the message is longer than the
 *    input box, an expanded input takes over in the same spot - same right edge and bottom - and
 *    grows leftwards across the video as you type, wrapping upwards at the video's left edge.
 *    It is a real input (cursor, selection, emote suggestions, Send / Enter). It hands back to
 *    the normal input when the message fits again, is sent or cleared.
 *
 * Both are drawn in the activity's top layer, positioned from the input every frame they show.
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Started from ChatSuggestions.attach (ChatFragment hook).
 */
object ChatInputExtras {

    fun attach(input: MultiAutoCompleteTextView, row: View, adapter: ArrayAdapter<*>?) {
        val activity = generateSequence(input.context) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? android.app.Activity } ?: return // Xtra5s: any screen with a chat (also Side-By-Side)
        val top = activity.findViewById<FrameLayout>(android.R.id.content) ?: return
        val density = input.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // ---- Red clear button ----
        val clear = TextView(input.context).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFE53935.toInt())
            }
            contentDescription = "Clear message"
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        top.addView(clear, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.START))

        // ---- Expanding input ----
        val big = MultiAutoCompleteTextView(input.context).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(0x99FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_PX, input.textSize)
            typeface = input.typeface
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xE6000000.toInt())
                setStroke(dp(1).coerceAtLeast(1), 0x99FFFFFF.toInt())
            }
            // Wraps onto more lines but keeps the keyboard's Send key.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false)
            maxLines = 12
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_FULLSCREEN
            threshold = 1
            adapter?.let { setAdapter(it) }
            setTokenizer(ChatFragment.SpaceTokenizer())
            elevation = dp(3).toFloat()
            visibility = View.GONE
        }
        top.addView(big, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        var syncing = false
        fun send() { row.findViewById<View>(R.id.send)?.takeIf { it.isShown }?.performClick() }
        fun visibleWidth() = input.width - input.totalPaddingLeft - input.totalPaddingRight
        fun overflows(text: CharSequence) = visibleWidth() > 0 && input.paint.measureText(text.toString()) > visibleWidth()

        /** Left edge of the video, if the chat is beside it (landscape, full-size player). */
        fun videoLeft(): Int? {
            if (input.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) return null
            val player = (activity as? MainActivity)?.playerFragment as? PlayerFragment ?: return null
            val root = player.view ?: return null
            if (!player.isResumed || !player.xtra5PlayerState().isMaximized) return null
            if (generateSequence(input as View) { it.parent as? View }.none { it === root }) return null
            val video = root.findViewById<View>(R.id.playerLayout) ?: return null
            val loc = IntArray(2).also { video.getLocationInWindow(it) }
            return loc[0]
        }

        fun hideBig(focusInput: Boolean) {
            if (big.visibility != View.VISIBLE) return
            val selection = big.selectionEnd.coerceIn(0, input.length())
            big.visibility = View.GONE
            if (focusInput) {
                input.requestFocus()
                input.setSelection(selection.coerceAtMost(input.length()))
            }
        }

        fun showBig() {
            if (big.visibility == View.VISIBLE) return
            syncing = true
            big.setText(input.text, false)
            syncing = false
            big.setSelection(input.selectionEnd.coerceIn(0, big.length()))
            big.visibility = View.VISIBLE
            big.requestFocus()
            (input.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(big, 0)
        }

        val inputLoc = IntArray(2)
        val topLoc = IntArray(2)
        fun place() {
            val text = input.text?.toString().orEmpty()
            val inputShown = input.isShown && input.isAttachedToWindow
            input.getLocationInWindow(inputLoc)
            top.getLocationInWindow(topLoc)
            val left = inputLoc[0] - topLoc[0]
            val topY = inputLoc[1] - topLoc[1]
            val right = left + input.width
            val bottom = topY + input.height
            // Expanding input: same right edge and bottom as the input, growing left and up.
            val videoLeft = videoLeft()
            if (big.visibility == View.VISIBLE && (!inputShown || videoLeft == null)) hideBig(focusInput = inputShown)
            if (big.visibility == View.VISIBLE) {
                val maxWidth = (right - (videoLeft!! - topLoc[0]) - dp(8)).coerceAtLeast(input.width)
                if (big.maxWidth != maxWidth) big.maxWidth = maxWidth
                if (big.minWidth != input.width) big.minWidth = input.width
                if (big.minHeight != input.height) big.minHeight = input.height
                val h = big.height.takeIf { it > 0 } ?: input.height
                val w = big.width.takeIf { it > 0 } ?: input.width
                val lp = big.layoutParams as FrameLayout.LayoutParams
                val x = right - w
                val y = bottom - h
                if (lp.leftMargin != x || lp.topMargin != y) {
                    lp.leftMargin = x
                    lp.topMargin = y
                    big.layoutParams = lp
                }
            }
            // Clear button: above the right end of the input (or the expanded input).
            val hasText = text.isNotEmpty()
            val showClear = inputShown && hasText
            if (showClear) {
                val boxTop = if (big.visibility == View.VISIBLE) (big.layoutParams as FrameLayout.LayoutParams).topMargin else topY
                val lp = clear.layoutParams as FrameLayout.LayoutParams
                val x = right - dp(32)
                val y = boxTop - dp(32) - dp(14)
                if (lp.leftMargin != x || lp.topMargin != y) {
                    lp.leftMargin = x
                    lp.topMargin = y
                    clear.layoutParams = lp
                }
            }
            val clearVisibility = if (showClear) View.VISIBLE else View.GONE
            if (clear.visibility != clearVisibility) clear.visibility = clearVisibility
        }

        clear.setOnClickListener {
            input.text?.clear()
            if (big.visibility == View.VISIBLE) {
                syncing = true
                big.setText("", false)
                syncing = false
                hideBig(focusInput = true)
            } else {
                input.requestFocus()
            }
            place()
        }

        // Normal input -> expanded input when it no longer fits (and back when it does).
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val text = s ?: return
                if (big.visibility == View.VISIBLE) {
                    // Changed from outside (sent, cleared, emote added): follow it.
                    if (big.text.toString() != text.toString()) {
                        syncing = true
                        big.setText(text, false)
                        big.setSelection(big.length())
                        syncing = false
                    }
                    if (!overflows(text)) hideBig(focusInput = true)
                } else if (input.hasFocus() && overflows(text) && videoLeft() != null) {
                    showBig()
                }
                place()
            }
        })
        // Expanded input -> normal input (which is what gets sent).
        big.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing || big.visibility != View.VISIBLE) return
                val text = s?.toString().orEmpty()
                syncing = true
                input.setText(text, false)
                input.setSelection(big.selectionEnd.coerceIn(0, input.length()))
                syncing = false
                if (!overflows(text)) hideBig(focusInput = true)
                place()
            }
        })
        big.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                send()
                true
            } else false
        }
        big.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                send()
                true
            } else false
        }
        big.setOnFocusChangeListener { _, hasFocus ->
            // Left the expanded input (tapped elsewhere): back to the normal input, keeping the text.
            if (!hasFocus && big.visibility == View.VISIBLE) big.post { if (!big.hasFocus()) hideBig(focusInput = false) }
        }

        // Keep both in place every frame they're on screen.
        val preDraw = ViewTreeObserver.OnPreDrawListener {
            if (clear.visibility == View.VISIBLE || big.visibility == View.VISIBLE || input.length() > 0) place()
            true
        }
        input.viewTreeObserver.addOnPreDrawListener(preDraw)
        input.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.viewTreeObserver.removeOnPreDrawListener(preDraw)
                top.removeView(clear)
                top.removeView(big)
            }
        })
    }
}
