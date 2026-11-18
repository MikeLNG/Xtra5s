package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.Preference
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.checkbox.MaterialCheckBox

/**
 * Xtra5s custom: Settings > UI > Automatic refresh. One window listing every automatic refresh /
 * reload / resume, each with a checkbox (all on by default).
 * [AutoRefreshPreference] is used straight from ui_preferences.xml, so no code hook is needed.
 */
object AutoRefreshSettings {

    class Item(val key: String, val title: String, val group: String)

    const val MINIMIZE = "xtra5_ar_minimize"
    const val RETURN_LISTS = "xtra5_ar_return_lists"
    const val RETURN_LIVE = "xtra5_ar_return_live"
    const val RESUME_SCREEN = "xtra5_ar_resume_screen"
    const val RESUME_MAXIMIZE = "xtra5_ar_resume_maximize"
    const val CHAT_REDRAW = "xtra5_ar_chat_redraw"
    const val LIVE_TAB = "xtra5_ar_live_tab"
    const val BACK_LIVE = "xtra5_ar_back_live"
    const val FOLLOWING_AGAIN = "xtra5_ar_following_again"
    const val FAVORITES = "xtra5_ar_favorites"
    const val DUAL_OFFLINE = "xtra5_ar_dual_offline"
    const val DUAL_RECONNECT = "xtra5_ar_dual_reconnect"
    const val DUAL_BADGES = "xtra5_ar_dual_badges"

    internal val items = listOf(
        Item(MINIMIZE, "Minimizing the video refreshes the lists behind it", "Video and app"),
        Item(RETURN_LISTS, "Coming back to the app (or waking the device) refreshes the lists", "Video and app"),
        Item(RETURN_LIVE, "Coming back to the app reloads an open live stream, jumping to live", "Video and app"),
        Item(RESUME_SCREEN, "Coming back from another screen of the app (e.g. Settings) plays the video again if it was playing", "Video and app"),
        Item(RESUME_MAXIMIZE, "Making a paused mini player full-size plays it (live streams jump to live)", "Video and app"),
        Item(CHAT_REDRAW, "Coming back from another screen of the app redraws the chat, so highlight changes show at once", "Video and app"),
        Item(LIVE_TAB, "Tapping the Live tab refreshes it and scrolls to the top", "Following"),
        Item(BACK_LIVE, "Back on a main page goes to Following > Live and refreshes it", "Following"),
        Item(FOLLOWING_AGAIN, "Pressing Following again refreshes the Live list", "Following"),
        Item(FAVORITES, "Adding or removing a favourite re-sorts the lists straight away", "Following"),
        Item(DUAL_OFFLINE, "Offline channels start playing by themselves when they go live (checked every minute)", "Dual Stream"),
        Item(DUAL_RECONNECT, "Dropped or ended streams reconnect by themselves", "Dual Stream"),
        Item(DUAL_BADGES, "Opening the Dual Stream tab checks who's live for the LIVE badges", "Dual Stream"),
    )

    fun enabled(context: Context, key: String) = context.prefs().getBoolean(key, true)

    internal fun summary(on: Int, total: Int) = when (on) {
        total -> "All on"
        0 -> "All off"
        else -> "$on of $total on"
    }

    fun summary(context: Context) = summary(items.count { enabled(context, it.key) }, items.size)

    fun show(context: Context, onSaved: () -> Unit) {
        val material = SideBySide.material(context)
        val dp = context.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val checks = LinkedHashMap<String, MaterialCheckBox>()
        val column = LinearLayout(material).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(12), px(8))
        }
        var group: String? = null
        items.forEach { item ->
            if (item.group != group) {
                group = item.group
                column.addView(TextView(material).apply {
                    text = item.group
                    setTypeface(typeface, Typeface.BOLD)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    alpha = 0.7f
                    setPadding(0, px(if (column.childCount == 0) 4 else 16), 0, px(4))
                })
            }
            val check = MaterialCheckBox(material).apply { isChecked = enabled(context, item.key) }
            checks[item.key] = check
            val row = LinearLayout(material).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                background = TypedValue().let { tv -> material.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true); material.getDrawable(tv.resourceId) }
                minimumHeight = px(48)
                setOnClickListener { check.toggle() }
            }
            row.addView(TextView(material).apply {
                text = item.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = px(8) })
            // The row handles the tap, so the checkbox itself doesn't take focus twice on TV.
            check.isFocusable = false
            check.isClickable = false
            row.addView(check, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        material.getAlertDialogBuilder()
            .setTitle("Automatic refresh")
            .setView(ScrollView(material).apply { addView(column) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                context.prefs().edit { checks.forEach { (key, box) -> putBoolean(key, box.isChecked) } }
                onSaved()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

/** Settings > UI > Automatic refresh: opens [AutoRefreshSettings.show]. */
class AutoRefreshPreference @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : Preference(context, attrs) {

    init {
        isIconSpaceReserved = false
        isSingleLineTitle = false
    }

    override fun onAttached() {
        super.onAttached()
        summary = AutoRefreshSettings.summary(context)
    }

    override fun onClick() {
        AutoRefreshSettings.show(context) { summary = AutoRefreshSettings.summary(context) }
    }
}
