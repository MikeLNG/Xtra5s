package com.github.andreyasadchy.xtra.custom

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import kotlin.math.abs

/**
 * Xtra5s custom: tapping the video (normal player or Dual Stream) or the chat's messages while
 * the message box has the cursor takes the cursor out of it: it stops flashing and the keyboard
 * closes. The box stays, with its text. The tap itself still does what it normally does.
 * Called from SoloVolume.onTouch, SideBySideActivity and ChatInputExtras.attach.
 */
object ChatInputFocus {

    /** Takes the cursor out of the focused text box, if there is one (touch only). */
    fun release(context: Context) {
        val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.firstNotNullOfOrNull { it as? Activity } ?: return
        val focused = activity.currentFocus as? EditText ?: return
        if (!focused.isInTouchMode) return // remote / keyboard: leave focus alone
        // In touch mode nothing else takes the focus, so the cursor just goes.
        focused.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(focused.windowToken, 0)
    }

    /** Taps (not scrolls) on the chat's messages release the cursor too. */
    fun watchChat(row: View) {
        val list = generateSequence(row.parent as? View) { it.parent as? View }
            .firstNotNullOfOrNull { it.findViewById<RecyclerView>(R.id.recyclerView) } ?: return
        if (list.getTag(R.id.xtra5s_chat_focus) == true) return
        list.setTag(R.id.xtra5s_chat_focus, true)
        val slop = ViewConfiguration.get(list.context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                    MotionEvent.ACTION_UP -> if (abs(e.x - downX) < slop && abs(e.y - downY) < slop) release(rv.context)
                }
                return false
            }
        })
    }
}
