package com.github.andreyasadchy.xtra.custom

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.appbar.AppBarLayout

/**
 * Xtra5 custom: keep the top bar (title bar, section tabs, sort bar) fixed instead of scrolling
 * away with the list, without the lift-on-scroll color change. On/off in Settings > UI > Top bar. Applies to every screen of the main
 * activity; the Settings screen is separate and unchanged.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Started from UpstreamUpdates.onMainActivityCreate (no extra upstream hook).
 */
object FixedTopBar {

    const val PREF_ENABLED = "xtra5_fixed_top_bar"

    fun register(activity: AppCompatActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (!activity.prefs().getBoolean(PREF_ENABLED, true)) return
                // Tab pages adjust their parent screen's bar after it was set up, so fix this
                // fragment's bars and its parents' bars, now and again once setup has finished.
                val fragments = generateSequence(f) { it.parentFragment }.toList()
                fun apply() = fragments.forEach { fragment ->
                    val view = fragment.view ?: return@forEach
                    // Channel and game pages keep their banner sliding away (TvNavigation);
                    // their top bar and tabs stay put anyway. Only the plain colour applies.
                    val keepScrolling = fragment is com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragment ||
                        fragment is com.github.andreyasadchy.xtra.ui.game.GamePagerFragment ||
                        fragment is com.github.andreyasadchy.xtra.ui.game.GameMediaFragment
                    findAppBars(view).forEach { fix(it, keepScrolling) }
                }
                apply()
                v.post { apply() }
            }
        }, true)
    }

    private fun findAppBars(view: View, out: MutableList<AppBarLayout> = mutableListOf()): List<AppBarLayout> {
        if (view is AppBarLayout) out.add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) findAppBars(view.getChildAt(i), out)
        return out
    }

    private fun fix(appBar: AppBarLayout, keepScrolling: Boolean = false) {
        if (keepScrolling) appBar.setTag(R.id.xtra5_keep_scrolling, true)
        val scrolls = appBar.getTag(R.id.xtra5_keep_scrolling) == true
        if (!scrolls) clearScrollFlags(appBar)
        // A fixed bar shouldn't change color while the list scrolls: no lift on scroll, no
        // elevation change, and a plain background in the theme's normal (un-lifted) color.
        appBar.isLiftOnScroll = false
        appBar.setLiftable(false)
        appBar.stateListAnimator = null
        appBar.elevation = 0f
        if (appBar.background !is ColorDrawable) {
            appBar.background = ColorDrawable(surfaceColor(appBar))
        }
        if (appBar.getTag(R.id.xtra5_fixed_top_bar) == null) {
            appBar.setTag(R.id.xtra5_fixed_top_bar, true)
            // Some screens (a channel's pages) turn scrolling back on when switching tabs.
            appBar.addOnOffsetChangedListener { bar, offset ->
                if (offset != 0 && bar.getTag(R.id.xtra5_keep_scrolling) != true) {
                    clearScrollFlags(bar)
                    bar.setExpanded(true, false)
                }
            }
        }
    }

    private fun surfaceColor(view: View): Int {
        val tv = TypedValue()
        val theme = view.context.theme
        return if (theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true)) {
            if (tv.resourceId != 0) view.context.getColor(tv.resourceId) else tv.data
        } else Color.BLACK
    }

    private fun clearScrollFlags(appBar: AppBarLayout) {
        for (i in 0 until appBar.childCount) {
            val child = appBar.getChildAt(i)
            val params = child.layoutParams as? AppBarLayout.LayoutParams ?: continue
            if (params.scrollFlags != 0) {
                params.scrollFlags = 0
                child.layoutParams = params
            }
        }
    }
}
