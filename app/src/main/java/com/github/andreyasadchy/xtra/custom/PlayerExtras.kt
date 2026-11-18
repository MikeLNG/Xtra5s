package com.github.andreyasadchy.xtra.custom

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.ui.player.PlayerFragment
import com.github.andreyasadchy.xtra.ui.settings.SettingsActivity

/**
 * Xtra5 custom: buttons in the player's top-right controls, part of the controls (they show and
 * hide with them): Chat commands (Xtra5s, "!": see BotCommands), Highlighted users (after the
 * quality cog) and Settings (just before the ⋮ menu), so none needs the video minimized.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Started from UpstreamUpdates.onMainActivityCreate (no extra upstream hook).
 */
object PlayerExtras {

    fun register(activity: MainActivity) {
        activity.supportFragmentManager.registerFragmentLifecycleCallbacks(object : FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
                if (f is PlayerFragment) {
                    addSettingsButton(activity, v)
                    addHighlightsButton(activity, v)
                    addCommandsButton(activity, f, v)
                }
            }
        }, true)
    }

    /** User highlights, between the quality (cog) button and the Settings button. */
    private fun addHighlightsButton(activity: MainActivity, view: View) {
        val row = view.findViewById<LinearLayout>(R.id.topRightLayout) ?: return
        if (row.findViewById<View>(R.id.xtra5_player_highlights) != null) return
        val template = row.findViewById<ImageButton>(R.id.menu)
        val button = ImageButton(row.context).apply {
            id = R.id.xtra5_player_highlights
            contentDescription = "Highlighted users"
            setImageResource(R.drawable.xtra5_palette)
            background = template?.background?.constantState?.newDrawable()?.mutate()
            imageTintList = template?.imageTintList
            scaleType = template?.scaleType ?: scaleType
            template?.let { setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
            setOnClickListener { HighlightDialogs.showUserList(activity) {} }
        }
        val size = (40 * row.resources.displayMetrics.density).toInt()
        val params = template?.layoutParams?.let { LinearLayout.LayoutParams(it as LinearLayout.LayoutParams) }
            ?: LinearLayout.LayoutParams(size, size)
        val quality = row.findViewById<View>(R.id.quality)
        val index = quality?.let { row.indexOfChild(it) + 1 } ?: row.indexOfChild(row.findViewById(R.id.xtra5_player_settings))
        row.addView(button, index.coerceIn(0, row.childCount), params)
    }

    /** Xtra5s: the channel's chat-bot commands ("!"), just before Highlighted users. */
    private fun addCommandsButton(activity: MainActivity, player: PlayerFragment, view: View) {
        val row = view.findViewById<LinearLayout>(R.id.topRightLayout) ?: return
        if (row.findViewById<View>(R.id.xtra5s_player_commands) != null) return
        val template = row.findViewById<ImageButton>(R.id.menu)
        val button = ImageButton(row.context).apply {
            id = R.id.xtra5s_player_commands
            contentDescription = "Chat commands"
            setImageResource(R.drawable.xtra5s_commands)
            background = template?.background?.constantState?.newDrawable()?.mutate()
            imageTintList = template?.imageTintList
            scaleType = template?.scaleType ?: scaleType
            template?.let { setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
            setOnClickListener {
                val state = player.xtra5PlayerState()
                val login = state.channelLogin
                if (login.isNullOrBlank()) {
                    android.widget.Toast.makeText(activity, "No channel to look up", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    BotCommands.show(activity, login, state.isLiveStream) { command -> BotCommands.insertInto(player.view, command) }
                }
            }
        }
        val size = (40 * row.resources.displayMetrics.density).toInt()
        val params = template?.layoutParams?.let { LinearLayout.LayoutParams(it as LinearLayout.LayoutParams) }
            ?: LinearLayout.LayoutParams(size, size)
        val highlights = row.findViewById<View>(R.id.xtra5_player_highlights)
        val index = highlights?.let { row.indexOfChild(it) } ?: row.childCount
        row.addView(button, index.coerceIn(0, row.childCount), params)
    }

    private fun addSettingsButton(activity: MainActivity, view: View) {
        val row = view.findViewById<LinearLayout>(R.id.topRightLayout) ?: return
        if (row.findViewById<View>(R.id.xtra5_player_settings) != null) return
        val template = row.findViewById<ImageButton>(R.id.menu)
        val button = ImageButton(row.context).apply {
            id = R.id.xtra5_player_settings
            contentDescription = activity.getString(R.string.settings)
            setImageResource(R.drawable.xtra5_tune)
            background = template?.background?.constantState?.newDrawable()?.mutate()
            imageTintList = template?.imageTintList
            scaleType = template?.scaleType ?: scaleType
            template?.let { setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
            setOnClickListener {
                activity.settingsResultLauncher?.launch(Intent(activity, SettingsActivity::class.java))
            }
        }
        val size = (40 * row.resources.displayMetrics.density).toInt()
        val params = template?.layoutParams?.let { LinearLayout.LayoutParams(it as LinearLayout.LayoutParams) }
            ?: LinearLayout.LayoutParams(size, size)
        val index = template?.let { row.indexOfChild(it) } ?: row.childCount
        row.addView(button, index.coerceAtLeast(0), params)
    }
}
