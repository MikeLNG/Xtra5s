package com.github.andreyasadchy.xtra.custom

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.target
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.XtraApp
import com.github.andreyasadchy.xtra.databinding.FragmentVideosListItemBinding
import com.github.andreyasadchy.xtra.model.ui.Game
import com.github.andreyasadchy.xtra.model.ui.Video
import com.github.andreyasadchy.xtra.ui.main.MainActivity
import com.github.andreyasadchy.xtra.util.C
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import com.github.andreyasadchy.xtra.util.prefs
import kotlinx.coroutines.launch

/**
 * Xtra5 custom: a Chapters button on every VOD card (top-right of the thumbnail). It loads that
 * VOD's chapters (only when pressed) and lists them in a dropdown from the button; picking one
 * starts the VOD from there, in fullscreen. Without chapters it lists the video's one category,
 * from 00:00.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hook: VideosAdapter.PagingViewHolder.bind.
 */
object VodChapters {

    fun bind(binding: FragmentVideosListItemBinding, item: Video?, fragment: Fragment) {
        val layout = binding.topLayout
        val density = layout.resources.displayMetrics.density
        val button = layout.findViewById(R.id.xtra5_vod_chapters) ?: ImageButton(layout.context).also { b ->
            b.id = R.id.xtra5_vod_chapters
            b.setImageResource(R.drawable.xtra5_chapters)
            b.contentDescription = "Chapters"
            b.scaleType = ImageView.ScaleType.CENTER
            b.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xAA000000.toInt())
            }
            val size = (34 * density).toInt()
            layout.addView(b, ConstraintLayout.LayoutParams(size, size).apply {
                topToTop = binding.thumbnail.id
                endToEnd = binding.thumbnail.id
                topMargin = (6 * density).toInt()
                marginEnd = (6 * density).toInt()
            })
        }
        val videoId = item?.id
        if (videoId.isNullOrBlank()) {
            button.visibility = View.GONE
            return
        }
        button.visibility = View.VISIBLE
        button.setOnClickListener { load(fragment, item, button) }
    }

    private fun load(fragment: Fragment, video: Video, anchor: View) {
        val context = fragment.context ?: return
        val app = context.applicationContext as? XtraApp ?: return
        val repository = app.xtraModule.graphQLRepository
        val networkLibrary = context.prefs().getString(C.NETWORK_LIBRARY, C.OKHTTP)
        val headers = TwitchApiHelper.getGQLHeaders(context)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val chapters = try {
                repository.loadQueryVideoMoments(networkLibrary, headers, video.id).data!!.video!!.moments!!.edges!!.mapNotNull { edge ->
                    edge?.node?.let {
                        Game(
                            name = it.details?.onGameChangeMomentDetails?.game?.displayName,
                            boxArtURL = it.details?.onGameChangeMomentDetails?.game?.boxArtURL,
                            vodPosition = it.positionMilliseconds,
                            vodDuration = it.durationMilliseconds,
                        )
                    }
                }
            } catch (e: Exception) {
                try {
                    repository.loadVideoGames(networkLibrary, headers, video.id).data!!.video.moments.edges.map { edge ->
                        edge.node.let {
                            Game(
                                name = it.details?.game?.displayName,
                                boxArtURL = it.details?.game?.boxArtURL,
                                vodPosition = it.positionMilliseconds,
                                vodDuration = it.durationMilliseconds,
                            )
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (!fragment.isAdded) return@launch
            // No chapters: one entry for the category the whole video stayed in, from 00:00.
            val list = chapters?.takeIf { it.isNotEmpty() } ?: listOf(
                Game(
                    name = video.gameName,
                    boxArtURL = video.gameId?.let { "https://static-cdn.jtvnw.net/ttv-boxart/$it-{width}x{height}.jpg" },
                    vodPosition = 0,
                    vodDuration = video.durationSeconds?.let { it * 1000 },
                )
            )
            if (anchor.isAttachedToWindow) show(fragment, video, list, anchor)
        }
    }

    /** The chapters as a dropdown attached to the Chapters button. */
    private fun show(fragment: Fragment, video: Video, chapters: List<Game>, anchor: View) {
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val rowHeight = dp(60)
        val secondary = TypedValue().also { ctx.theme.resolveAttribute(android.R.attr.textColorSecondary, it, true) }.let { tv ->
            if (tv.resourceId != 0) ctx.getColor(tv.resourceId) else tv.data
        }
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = chapters.size
            override fun getItem(position: Int) = chapters[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val chapter = chapters[position]
                val row = (convertView as? LinearLayout) ?: LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = rowHeight
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    addView(ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP },
                        LinearLayout.LayoutParams(dp(36), dp(48)).apply { marginEnd = dp(12) })
                    addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(ctx).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                            maxLines = 1
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        })
                        addView(TextView(ctx).apply {
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                            setTextColor(secondary)
                        })
                    })
                }
                val image = row.getChildAt(0) as ImageView
                val texts = row.getChildAt(1) as LinearLayout
                image.setImageDrawable(null)
                chapter.boxArt?.let { url -> ctx.imageLoader.enqueue(ImageRequest.Builder(ctx).data(url).target(image).build()) }
                (texts.getChildAt(0) as TextView).text = chapter.name ?: "Unknown"
                val start = startText((chapter.vodPosition ?: 0) / 1000L)
                val length = chapter.vodDuration?.takeIf { it > 0 }?.let { " (" + lengthText(it / 1000L) + ")" } ?: ""
                (texts.getChildAt(1) as TextView).text = start + length
                return row
            }
        }
        android.widget.ListPopupWindow(ctx).apply {
            anchorView = anchor
            setAdapter(adapter)
            width = dp(280)
            height = rowHeight * chapters.size.coerceAtMost(5) + dp(8)
            isModal = true
            setOnItemClickListener { _, _, position, _ ->
                dismiss()
                play(fragment, video, chapters[position].vodPosition?.toLong() ?: 0L)
            }
            show()
            listView?.isFocusable = true
            listView?.requestFocus()
        }
    }

    /** Starts the VOD at [positionMs], in fullscreen. */
    private fun play(fragment: Fragment, video: Video, positionMs: Long) {
        val activity = fragment.activity as? MainActivity ?: return
        activity.startVideo(video, positionMs, true)
        if (activity.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            // Same as the player's fullscreen button.
            activity.window.decorView.postDelayed({
                if (!activity.isFinishing) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }, 300L)
        }
    }

    // Xtra5s: "start (length)", e.g. "00:00 (3hr 12m 02s)", "03:12:03 (1hr 20m 56s)".

    /** Where a chapter starts: "mm:ss", or "hh:mm:ss" from an hour on. */
    internal fun startText(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%02d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** How long a chapter is: "3hr 12m 02s", or "8m 40s" under an hour. */
    internal fun lengthText(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%dhr %02dm %02ds".format(h, m, sec) else "%dm %02ds".format(m, sec)
    }
}