package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.paging.PagingSource
import androidx.paging.PagingState
import androidx.recyclerview.widget.RecyclerView
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.following.channels.FollowedChannelsAdapter
import com.github.andreyasadchy.xtra.ui.following.channels.FollowedChannelsSortDialog
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.prefs
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * Xtra5 custom: "Favourites first" on Following > Channels.
 *  - A switch in the Channels sort dropdown (saved; the list reloads when it changes).
 *  - When on, every followed channel is loaded at once, favourites are grouped at the top and
 *    both groups use the sort and order picked in the dropdown. (Normally the list loads 100
 *    channels at a time, so favourites further down couldn't be moved up.)
 *  - When off, the list is exactly as in official Xtra.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hook: FollowedChannelsViewModel (the list's data source is passed through [wrap]).
 */
object FavoriteChannels {

    const val PREF = "xtra5_channels_favorites_first"
    private const val MAX_PAGES = 50 // 100 channels each

    fun enabled(context: Context) = context.prefs().getBoolean(PREF, false)

    fun wrap(context: Context, source: PagingSource<Int, User>, sort: String, order: String): PagingSource<Int, User> =
        if (enabled(context)) FavoritesFirst(source, sort, order) else source

    private class FavoritesFirst(
        private val inner: PagingSource<Int, User>,
        private val sort: String,
        private val order: String,
    ) : PagingSource<Int, User>() {

        override fun getRefreshKey(state: PagingState<Int, User>): Int? = null

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, User> {
            if (params.key != null) return LoadResult.Page(emptyList(), null, null)
            val all = ArrayList<User>()
            var key: Int? = null
            var pages = 0
            while (true) {
                val result = if (pages == 0) {
                    inner.load(LoadParams.Refresh(null, params.loadSize, false))
                } else {
                    inner.load(LoadParams.Append(key!!, params.loadSize, false))
                }
                val page = result as? LoadResult.Page<Int, User>
                if (page == null) {
                    if (all.isEmpty()) return result
                    break // keep what loaded
                }
                all.addAll(page.data)
                pages++
                key = page.nextKey ?: break
                if (pages >= MAX_PAGES) break
            }
            val unique = all.distinctBy { it.id ?: it.login ?: it.hashCode().toString() }
            return LoadResult.Page(group(sorted(unique)), null, null)
        }

        private fun sorted(list: List<User>): List<User> {
            val selector: (User) -> String? = when (sort) {
                FollowedChannelsSortDialog.SORT_FOLLOWED_AT -> { u -> u.followedAt }
                FollowedChannelsSortDialog.SORT_ALPHABETICALLY -> { u -> u.login }
                else -> { u -> u.lastBroadcast }
            }
            // Same rules as official Xtra's sort.
            return if (order == FollowedChannelsSortDialog.ORDER_ASC) {
                list.sortedWith(compareBy(nullsLast(), selector))
            } else {
                list.sortedWith(compareByDescending(nullsFirst(), selector))
            }
        }

        private fun group(list: List<User>) = list.sortedBy { if (Favorites.isFavorite(it.id)) 0 else 1 }
    }

    /** Adds the switch to the Channels sort dropdown and reloads the list if it was changed. */
    fun register(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: android.os.Bundle?) {
                if (f is FollowedChannelsSortDialog) addSwitch(f, v)
            }

            override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                if (f !is FollowedChannelsSortDialog || changed.remove(f) != true) return
                f.parentFragment?.view?.let { findList(it) }?.let { (it.adapter as? FollowedChannelsAdapter)?.refresh() }
            }
        }, true)
    }

    private val changed = java.util.WeakHashMap<Fragment, Boolean>()

    private fun addSwitch(dialog: Fragment, root: View) {
        val apply = root.findViewById<View>(R.id.apply) ?: return
        // The row holding Apply / Save as default, inside the dialog's main column.
        var row: View = apply
        while (row.parent !is LinearLayout || (row.parent as View).parent !== root) {
            row = row.parent as? View ?: return
            if (row === root) return
        }
        val column = row.parent as LinearLayout
        val context = root.context
        val switch = MaterialSwitch(context).apply {
            text = "Favourites first"
            isChecked = enabled(context)
            setOnCheckedChangeListener { _, checked ->
                context.prefs().edit { putBoolean(PREF, checked) }
                changed[dialog] = true
            }
        }
        column.addView(switch, column.indexOfChild(row), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * context.resources.displayMetrics.density).toInt()
            marginStart = (5 * context.resources.displayMetrics.density).toInt()
        })
    }

    /** Favourites changed: regroup the Channels list if it is sorted favourites first. */
    fun onFavoritesChanged(lists: List<RecyclerView>) {
        val context = lists.firstOrNull()?.context ?: return
        if (!enabled(context)) return
        lists.mapNotNull { it.adapter as? FollowedChannelsAdapter }.forEach { it.refresh() }
    }

    private fun findList(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findList(view.getChildAt(i))?.let { return it }
        return null
    }
}
