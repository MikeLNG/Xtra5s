package com.github.andreyasadchy.xtra.custom

import android.os.SystemClock
import com.github.andreyasadchy.xtra.model.VideoQuality

/**
 * Xtra5 custom: open one stream in audio-only quality (long-press on a stream card) without
 * changing the default or saved quality. The request is used by the next stream's default quality
 * choice (BasePlaybackService.setDefaultQuality), and expires if that doesn't happen soon.
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: end of BasePlaybackService.setDefaultQuality(); the "save quality" line in
 * ExoPlayerService and MediaPlayerService; requested from StreamCards.
 */
object AudioOnlyOnce {

    private const val EXPIRES_MS = 30_000L
    private var requestedAt = 0L
    /** Xtra5s: a quality ("720p", "Source"...) for the next stream instead of audio only. */
    private var requestedQuality: String? = null
    private var pickedName: String? = null

    fun request() {
        requestedQuality = null
        requestedAt = SystemClock.uptimeMillis()
    }

    /** Xtra5s (Dual Stream in split screen): the next stream opens at [quality], just this once. */
    fun requestQuality(quality: String) {
        requestedQuality = quality
        requestedAt = SystemClock.uptimeMillis()
    }

    /**
     * Called when the next stream picks its default quality: the one requested for it, or null.
     * Audio only (long-press), or the closest quality at or below a requested one ("720p" picks
     * 720p60 / 720p, else the next lower; "Source" the best).
     */
    fun pick(qualities: List<VideoQuality>?): VideoQuality? {
        val target = requestedQuality
        requestedQuality = null
        pickedName = null
        if (!consume()) return null
        val chosen = choose(qualities ?: return null, target)
        pickedName = chosen?.name
        if (chosen == null) active = false
        return chosen
    }

    /** The quality [target] asks for from [list] (null target: audio only). */
    internal fun choose(list: List<VideoQuality>, target: String?): VideoQuality? {
        if (target == null) return list.find { it.name == VideoQuality.AUDIO_ONLY_QUALITY }
        val real = list.filter {
            it.name != VideoQuality.AUTO_QUALITY && it.name != VideoQuality.AUDIO_ONLY_QUALITY &&
                it.name != VideoQuality.CHAT_ONLY_QUALITY && (it.resolution ?: 0) > 0
        }
        val order = compareBy<VideoQuality>({ it.resolution ?: 0 }, { it.frameRate ?: 0f })
        val height = target.removeSuffix("p").toIntOrNull()
        return when {
            target == SideBySide.SOURCE || height == null -> real.maxWithOrNull(order)
            else -> real.filter { (it.resolution ?: 0) <= height }.maxWithOrNull(order) ?: real.minWithOrNull(order)
        }
    }

    /** Whether the current stream's audio-only quality came from a long-press (not the menu). */
    private var active = false

    /** True once, for the stream opened right after request(). */
    fun consume(): Boolean {
        val pending = requestedAt != 0L && SystemClock.uptimeMillis() - requestedAt < EXPIRES_MS
        requestedAt = 0L
        active = pending
        return pending
    }

    /**
     * Called before official Xtra saves the current quality as the "saved" quality: true (don't
     * save) for the one-off audio-only quality, so it never becomes the default for later streams.
     */
    fun skipSave(qualityName: String?): Boolean {
        if (!active) return false
        if (qualityName == VideoQuality.AUDIO_ONLY_QUALITY || (qualityName != null && qualityName == pickedName)) return true
        active = false // the user picked another quality: save it as normal
        return false
    }
}
