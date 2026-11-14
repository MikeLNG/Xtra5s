package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.preference.Preference
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder

/**
 * Xtra5 custom: Settings > Shortcuts, a read-only list of every press and gesture Xtra5 adds,
 * grouped by screen. The text is the xtra5_shortcuts array in xtra5_strings.xml: update it
 * whenever a shortcut is added or changed.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Referenced by class name from res/xml/root_preferences.xml, so no code hooks are needed.
 */
@Keep
class ShortcutsPreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : Preference(context, attrs) {

    override fun onClick() {
        val builder = context.getAlertDialogBuilder()
        val ctx = builder.context
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
        fun attrColor(attr: Int): Int {
            val tv = TypedValue()
            ctx.theme.resolveAttribute(attr, tv, true)
            return if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
        }
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(12))
        }
        ctx.resources.getStringArray(R.array.xtra5_shortcuts).forEach { item ->
            list.addView(TextView(ctx).apply {
                when {
                    item.startsWith("# ") -> {
                        text = item.removePrefix("# ")
                        setTypeface(typeface, Typeface.BOLD)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        setTextColor(attrColor(androidx.appcompat.R.attr.colorPrimary))
                        setPadding(0, dp(16), 0, dp(4))
                    }
                    item.startsWith("- ") -> {
                        text = "•  " + item.removePrefix("- ")
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        setPadding(0, dp(4), 0, 0)
                    }
                    item.startsWith("Setting: ") -> {
                        text = item
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(attrColor(android.R.attr.textColorSecondary))
                        setPadding(dp(14), dp(1), 0, 0)
                    }
                    else -> {
                        text = item
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setTextColor(attrColor(android.R.attr.textColorSecondary))
                    }
                }
            })
        }
        builder.setTitle(R.string.xtra5_shortcuts_title)
            .setView(ScrollView(ctx).apply { addView(list) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
