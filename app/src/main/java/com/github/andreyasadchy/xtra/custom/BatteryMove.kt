package com.github.andreyasadchy.xtra.custom

import android.view.View
import android.view.ViewGroup
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Xtra5s custom: where the battery percentage sits in the player's top-right button row
 * (Settings > Player > Battery percentage > Position in the button row): how many buttons are to
 * its right, 0 = far right, right of the ⋮ menu (the default). Counted from the right, so it keeps
 * its place when a button comes or goes; more than there are = far left. BatteryIndicator opens a
 * gap in the row (a spacer) and places the label over it.
 */
object BatteryMove {

    const val PREF_SLOT = "xtra5s_battery_row_position"
    const val SPACER_TAG = "xtra5s_battery_spacer"

    fun slot(view: View): Int = view.context.prefs().getString(PREF_SLOT, "0")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** The row's buttons that show, left to right (not the battery's spacer). */
    fun buttons(row: ViewGroup): List<View> =
        (0 until row.childCount).map { row.getChildAt(it) }.filter { it.tag != SPACER_TAG && it.visibility == View.VISIBLE }

    /**
     * The label's left edge: the row's right edge at [rowRight], minus the [rightWidths] of the
     * buttons right of it, minus the gap ([spacer] wide) it sits centred in.
     */
    internal fun labelLeft(rowRight: Int, rightWidths: List<Int>, spacer: Int, label: Int): Int =
        rowRight - rightWidths.sum() - spacer + (spacer - label) / 2
}
