package com.github.andreyasadchy.xtra.custom

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.viewpager2.widget.ViewPager2

/**
 * Xtra5 custom: fixes tab pages showing the wrong content after the tabs were changed in
 * Settings (enabled, disabled or reordered). Official Xtra's tab bars remember their pages by
 * position, so coming back after a change could restore e.g. the old Videos page under the new
 * "Channels" label.
 *
 * Each tab bar now remembers the tab setup it was built with. If it comes back with a different
 * setup, the remembered pages are dropped and fresh ones are made. Unchanged setups are untouched.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: one line before the adapter is set in the Following, Channel, Game, Search and
 * Saved tab screens.
 */
object StaleTabs {

    private const val KEY = "xtra5_tabs"
    private val pageTag = Regex("f\\d+")

    fun check(fragment: Fragment, viewPager: ViewPager2, tabs: List<String>) {
        val current = tabs.joinToString(",")
        val args = fragment.arguments
        val previous = args?.getString(KEY)
        if (previous != null && previous != current) {
            val fm = fragment.childFragmentManager
            val stale = fm.fragments.filter { it.tag?.matches(pageTag) == true }
            if (stale.isNotEmpty()) {
                try {
                    fm.beginTransaction().apply { stale.forEach { remove(it) } }.commitNow()
                } catch (_: Exception) {}
            }
            // Don't let the pager restore its old pages either; saving works again right after.
            viewPager.isSaveEnabled = false
            viewPager.post { viewPager.isSaveEnabled = true }
        }
        if (args != null) {
            args.putString(KEY, current)
        } else {
            try { fragment.arguments = Bundle().apply { putString(KEY, current) } } catch (_: Exception) {}
        }
    }
}
