package com.github.andreyasadchy.xtra.custom

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import com.github.andreyasadchy.xtra.R
import com.github.andreyasadchy.xtra.model.chat.Image
import com.github.andreyasadchy.xtra.util.prefs
import java.lang.ref.WeakReference

/**
 * Xtra5 custom: synced animated emotes. Every copy of the same animated emote (same image, same
 * size) draws one shared animation, so all copies are always on the same frame.
 *
 * The first loaded animation for an emote becomes the shared one; later copies get a lightweight
 * wrapper that draws it. Wrappers are not Animatable, so official Xtra skips its per-message
 * animation setup for them. Up to MAX_SHARED emotes are kept; past that the least recently used
 * one that is not on screen is released (Xtra5s: one still on screen would freeze, so it stays).
 * 7TV overlay (zero-width) emotes are drawn through another path and are not synced. Only chat
 * lists (see attachTo) are synced. Each shared animation belongs to the chat that loaded it and is
 * released when that chat closes (Xtra5s: only that chat's; with two chats on screen the other one
 * keeps its own and re-loads any it was borrowing).
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hook: ChatAdapterUtils.loadImages (after an emote is loaded and sized).
 */
object EmoteSync {

    const val PREF_ENABLED = "xtra5_sync_emotes"
    private const val MAX_SHARED = 200 // Xtra5s: was 100; Side-By-Side shows two chats

    private val handler = Handler(Looper.getMainLooper())
    private var anyPaused = false

    /** One shared animation and every chat row currently showing it. */
    private class Shared(val drawable: Drawable, owner: View?) : Drawable.Callback {
        val views = ArrayList<WeakReference<View>>()
        /** The chat list whose image loader loaded [drawable]; it frees it when that chat closes. */
        val owner = WeakReference(owner)
        /** Released: its chat closed, so its frames may be freed. Never drawn again. */
        var released = false
        /** Stopped because no row showing it is on screen (battery). */
        var paused = false

        fun anyVisible() = views.any { ref ->
            ref.get()?.let { it.isAttachedToWindow && it.windowVisibility == View.VISIBLE && it.isShown } == true
        }

        fun start() {
            paused = false
            (drawable as Animatable).let { if (!it.isRunning) it.start() }
        }

        fun addView(view: View) {
            views.removeAll { it.get() == null }
            if (views.none { it.get() === view }) views.add(WeakReference(view))
        }

        override fun invalidateDrawable(who: Drawable) {
            var alive = false
            views.forEach { ref -> ref.get()?.let { alive = true; it.invalidate() } }
            if (!alive) views.clear()
            // Nothing on screen shows it (scrolled away, chat hidden, app in the background):
            // stop decoding frames until a row showing it is visible again.
            if (!paused && !anyVisible()) {
                paused = true
                anyPaused = true
                handler.post { if (paused) (drawable as Animatable).stop() }
            }
        }

        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            handler.postAtTime(what, who, `when`)
        }

        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            handler.removeCallbacks(what, who)
        }
    }

    /** Access-ordered: least recently used first. Trimmed by [trim]. */
    private val shared = LinkedHashMap<String, Shared>(16, 0.75f, true)

    /**
     * Keeps at most MAX_SHARED animations: releases the least recently used ones that no row on
     * screen shows. Ones still on screen are skipped, so a visible emote never freezes; if every
     * one is on screen the limit is exceeded until some scroll away.
     */
    private fun trim() {
        if (shared.size <= MAX_SHARED) return
        val iterator = shared.entries.iterator()
        while (shared.size > MAX_SHARED && iterator.hasNext()) {
            val entry = iterator.next().value
            if (entry.anyVisible()) continue
            releaseEntry(entry)
            iterator.remove()
        }
    }

    /**
     * Draws the shared animation inside one chat row. A plain Drawable (not a DrawableWrapper,
     * which would take over the shared animation's callback) and not Animatable.
     */
    private class SyncedDrawable(private val target: Shared) : Drawable() {
        init {
            bounds = target.drawable.bounds
        }

        override fun draw(canvas: Canvas) {
            if (!target.released) target.drawable.draw(canvas)
        }

        override fun getIntrinsicWidth() = target.drawable.intrinsicWidth
        override fun getIntrinsicHeight() = target.drawable.intrinsicHeight
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /**
     * Marks [chatList] as the main chat. The shared animations are released when it goes away,
     * because the image loader frees them together with the chat.
     */
    fun attachTo(chatList: View) {
        chatList.setTag(R.id.xtra5_sync_emotes, true)
        // The chat shown again (or re-laid out): restart paused animations that are visible now.
        chatList.viewTreeObserver.addOnGlobalLayoutListener { resumeVisible() }
        chatList.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = release(v)
        })
    }

    /** A chat row came on screen: restart the paused animations it shows. */
    fun onRowAttached(view: View) {
        if (!anyPaused) return
        shared.values.forEach { entry -> if (entry.paused && entry.views.any { it.get() === view }) entry.start() }
        anyPaused = shared.values.any { it.paused }
    }

    private fun resumeVisible() {
        if (!anyPaused) return
        shared.values.forEach { entry -> if (entry.paused && entry.anyVisible()) entry.start() }
        anyPaused = shared.values.any { it.paused }
    }

    private fun releaseEntry(entry: Shared) {
        entry.released = true
        (entry.drawable as? Animatable)?.stop()
        entry.drawable.callback = null
        handler.removeCallbacksAndMessages(entry.drawable)
    }

    /**
     * [chatList] closed: release the animations it loaded (its image loader frees them). Another
     * chat still on screen that showed some of them re-draws its messages, loading its own copies.
     */
    private fun release(chatList: View) {
        var borrowed = false
        val iterator = shared.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val owner = entry.owner.get()
            if (owner != null && owner !== chatList) continue
            if (entry.views.any { ref -> ref.get()?.let { it.isAttachedToWindow && it.parent !== chatList } == true }) borrowed = true
            releaseEntry(entry)
            iterator.remove()
        }
        anyPaused = shared.values.any { it.paused }
        if (borrowed) handler.post { ChatLive.refreshChat() }
    }

    /**
     * Returns the drawable to show for [image] in [itemView]: a synced copy for animated emotes,
     * or [loaded] unchanged when syncing doesn't apply.
     */
    fun sync(context: Context, loaded: Drawable, image: Image, itemView: View, animate: Boolean): Drawable {
        if (!animate || !image.isAnimated || loaded !is Animatable) return loaded
        if (!context.prefs().getBoolean(PREF_ENABLED, true)) return loaded
        // Only the main chat list (see attachTo): its animations live as long as the chat does.
        if ((itemView.parent as? View)?.getTag(R.id.xtra5_sync_emotes) != true) return loaded
        val source = image.localData?.let { "local:" + it.contentHashCode() } ?: image.url1x ?: return loaded
        val key = "$source@${loaded.bounds.width()}x${loaded.bounds.height()}"
        val entry = shared[key] ?: Shared(loaded, itemView.parent as? View).also { entry ->
            shared[key] = entry
            loaded.callback = entry
            loaded.setVisible(true, false)
        }
        entry.addView(itemView)
        entry.start()
        trim()
        return SyncedDrawable(entry)
    }
}
