package com.github.andreyasadchy.xtra.custom

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.navigation.findNavController
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.ui.User
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragment
import com.github.andreyasadchy.xtra.ui.channel.ChannelPagerFragmentDirections
import com.google.android.material.tabs.TabLayout

/**
 * Xtra5s custom: long-press a card on Following > Channels (not its profile picture, which
 * toggles the favourite) to open that channel's page straight on its Chat tab, live or offline.
 * On TV (with TV shortcuts on), holding OK on such a card does the same instead of refreshing.
 * Set up from Favorites.bindChannel (no extra upstream hook).
 */
object ChannelChat {

    private val handler = Handler(Looper.getMainLooper())

    fun bind(card: View, user: User?) {
        if (user == null || (user.id == null && user.login == null)) {
            card.setOnLongClickListener(null)
            card.setTag(R.id.xtra5s_channel_chat, null)
            return
        }
        card.setTag(R.id.xtra5s_channel_chat, true)
        card.setOnLongClickListener { view ->
            open(view, user)
            true
        }
    }

    /** TV: holding OK on a Channels card opens its chat. False when the focused view isn't one. */
    fun onHoldOk(activity: Activity): Boolean {
        val focused = activity.currentFocus ?: return false
        if (focused.getTag(R.id.xtra5s_channel_chat) != true) return false
        return focused.performLongClick()
    }

    private fun open(view: View, user: User) {
        val activity = view.context as? FragmentActivity ?: generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<FragmentActivity>().firstOrNull() ?: return
        val manager = activity.supportFragmentManager
        // Once the channel page is shown, switch it to its Chat tab.
        val callbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f !is ChannelPagerFragment) return
                manager.unregisterFragmentLifecycleCallbacks(this)
                selectChat(activity, v, 0)
            }
        }
        manager.registerFragmentLifecycleCallbacks(callbacks, true)
        // In case the page never opens, stop waiting.
        handler.postDelayed({ manager.unregisterFragmentLifecycleCallbacks(callbacks) }, 5000L)
        try {
            view.findNavController().navigate(
                ChannelPagerFragmentDirections.actionGlobalChannelPagerFragment(
                    channelId = user.id,
                    channelLogin = user.login,
                    channelName = user.name,
                    channelImage = user.profileImage,
                )
            )
        } catch (_: Exception) {
            manager.unregisterFragmentLifecycleCallbacks(callbacks)
        }
    }

    /** Picks the Chat tab (the tabs can appear a moment after the page). */
    private fun selectChat(activity: Activity, page: View, attempt: Int) {
        handler.postDelayed({
            val tabs = page.findViewById<TabLayout>(R.id.tabLayout)
            val chat = activity.getString(R.string.chat)
            val tab = tabs?.let { (0 until it.tabCount).mapNotNull { i -> it.getTabAt(i) }.firstOrNull { t -> t.text?.toString() == chat } }
            when {
                tab != null -> tab.select()
                attempt < 10 && (tabs == null || tabs.tabCount == 0) -> selectChat(activity, page, attempt + 1)
                else -> Toast.makeText(activity, "Chat is hidden in Settings > UI > Channel tabs", Toast.LENGTH_LONG).show()
            }
        }, if (attempt == 0) 50L else 100L)
    }
}
