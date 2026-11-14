package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.databinding.FragmentFollowedChannelsListItemBinding
import com.github.andreyasadchy.xtra.databinding.FragmentStreamsListItemBinding
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.model.ui.Stream
import com.github.andreyasadchy.xtra.util.prefs

/**
 * Xtra5 custom: favourite streamers.
 *  - Long-press a streamer's profile picture on a live stream card to favourite / unfavourite.
 *  - Favourites get a gold ring around the outside of the profile picture and a gold star on top of it, centred at
 *    the bottom and hanging slightly below.
 *  - Following > Live lists favourites first (A-Z), then everyone else (A-Z).
 *  - Following > Channels: same long-press and look (the order there doesn't change), so offline
 *    channels can be favourited for when they go live.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: the sort line in FollowedStreamsDataSource; stream cards via StreamCards.
 */
object Favorites {

    private const val PREF = "xtra5_favorites"
    private const val GOLD = 0xFFFFC107.toInt()

    private var appContext: Context? = null

    // Lists showing stream / channel cards, so a change redraws every card's ring and star
    // (cards that only move when the list re-sorts aren't redrawn by the list itself).
    private val lists = ArrayList<java.lang.ref.WeakReference<androidx.recyclerview.widget.RecyclerView>>()

    private fun remember(card: View) {
        val list = card.parent as? androidx.recyclerview.widget.RecyclerView ?: return
        lists.removeAll { it.get() == null }
        if (lists.none { it.get() === list }) lists.add(java.lang.ref.WeakReference(list))
    }

    private fun redrawAll() {
        lists.mapNotNull { it.get() }.forEach { list -> list.adapter?.let { it.notifyItemRangeChanged(0, it.itemCount) } }
    }
    private var ids: MutableSet<String>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun favorites(): MutableSet<String> {
        ids?.let { return it }
        val loaded = appContext?.prefs()?.getStringSet(PREF, null)?.toMutableSet() ?: mutableSetOf()
        ids = loaded
        return loaded
    }

    fun isFavorite(channelId: String?) = channelId != null && channelId in favorites()

    private fun toggle(channelId: String): Boolean {
        val set = favorites()
        val nowFavorite = if (channelId in set) { set.remove(channelId); false } else { set.add(channelId); true }
        appContext?.prefs()?.edit { putStringSet(PREF, HashSet(set)) }
        return nowFavorite
    }

    /** Following > Live order: favourites first (A-Z), then the rest (A-Z). */
    fun sortFollowed(list: MutableList<Stream>) {
        list.sortWith(compareBy<Stream>({ !isFavorite(it.channelId) }, { (it.channelName ?: it.channelLogin ?: "").lowercase() }))
    }

    /** Called for every live stream card (from StreamCards). Toggling re-sorts the list. */
    fun bind(binding: FragmentStreamsListItemBinding, stream: Stream, fragment: Fragment) {
        binding.root.post { remember(binding.root) }
        decorate(binding.userImage, stream.channelId, stream.channelName ?: stream.channelLogin) {
            fragment.activity?.let { RefreshButton.refreshCurrentScreen(it, showMessage = false) } // re-sort
            FavoriteChannels.onFavoritesChanged(lists.mapNotNull { it.get() }) // Channels, if favourites first
        }
    }

    /** Following > Channels cards: same toggle and look; the order only changes with "Favourites first". */
    fun bindChannel(binding: FragmentFollowedChannelsListItemBinding, user: User) {
        remember(binding.root)
        decorate(binding.userImage, user.id, user.name ?: user.login) { reloadLiveLists() }
    }

    /**
     * Re-sorts the live stream lists in the background. The Channels page only regroups when it
     * is set to "Favourites first" (see FavoriteChannels).
     */
    private fun reloadLiveLists() {
        val alive = lists.mapNotNull { it.get() }
        alive.mapNotNull { it.adapter }
            .filterIsInstance<com.github.andreyasadchy.xtra.ui.common.StreamsAdapter>()
            .forEach { it.refresh() }
        FavoriteChannels.onFavoritesChanged(alive)
    }

    /** Gold ring + star on [avatar] for favourites; long-press toggles. */
    private fun decorate(avatar: ImageView, channelId: String?, name: String?, onToggled: () -> Unit) {
        val layout = avatar.parent as? ConstraintLayout ?: return
        val ringWidth = dp(layout, RING_DP)
        // A true border: the ring wraps around the outside of the picture, which stays fully visible.
        val ring = layout.findViewById(R.id.xtra5_favorite_ring) ?: View(layout.context).also { ring ->
            ring.id = R.id.xtra5_favorite_ring
            ring.isClickable = false
            ring.isFocusable = false
            ring.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(ringWidth, GOLD)
            }
            layout.addView(ring, ConstraintLayout.LayoutParams(0, 0).apply {
                startToStart = avatar.id
                endToEnd = avatar.id
                topToTop = avatar.id
                bottomToBottom = avatar.id
            })
            // Follow the picture's size (card size settings can change it).
            avatar.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                val w = (r - l) + 2 * ringWidth
                val h = (b - t) + 2 * ringWidth
                val lp = ring.layoutParams
                if (lp.width != w || lp.height != h) v.post { ring.layoutParams = lp.apply { width = w; height = h } }
            }
        }
        val star = layout.findViewById(R.id.xtra5_favorite_star) ?: ImageView(layout.context).also { star ->
            star.id = R.id.xtra5_favorite_star
            star.setImageResource(R.drawable.xtra5_star)
            star.isClickable = false
            star.isFocusable = false
            val size = dp(layout, 20)
            layout.addView(star, ConstraintLayout.LayoutParams(size, size).apply {
                startToStart = avatar.id
                endToEnd = avatar.id
                bottomToBottom = avatar.id
            })
            star.translationY = dp(layout, 7 + RING_DP).toFloat() // hangs slightly below the ring (outside the picture)
        }
        if (layout.indexOfChild(star) != layout.childCount - 1) star.bringToFront() // above the ring
        fun show(favorite: Boolean) {
            val visible = favorite && avatar.visibility == View.VISIBLE
            star.visibility = if (visible) View.VISIBLE else View.GONE
            ring.visibility = if (visible) View.VISIBLE else View.GONE
            avatar.foreground = null
            val size = avatar.layoutParams.width.takeIf { it > 0 } ?: avatar.width
            if (size > 0) ring.layoutParams = ring.layoutParams.apply {
                width = size + 2 * ringWidth
                height = (avatar.layoutParams.height.takeIf { it > 0 } ?: avatar.height.takeIf { it > 0 } ?: size) + 2 * ringWidth
            }
        }
        show(isFavorite(channelId))
        if (channelId == null) {
            avatar.setOnLongClickListener(null)
            return
        }
        avatar.setOnLongClickListener {
            val favorite = toggle(channelId)
            show(favorite)
            val label = name ?: "Streamer"
            Toast.makeText(layout.context, if (favorite) "$label added to favourites" else "$label removed from favourites", Toast.LENGTH_SHORT).show()
            redrawAll() // every open list shows the change
            onToggled()
            true
        }
    }

    private const val RING_DP = 3

    private fun dp(view: View, value: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), view.resources.displayMetrics).toInt()
}
