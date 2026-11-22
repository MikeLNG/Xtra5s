package com.github.andreyasadchy.xtra.custom

import android.content.Context
import com.github.andreyasadchy.xtra.BuildConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Xtra5s custom: the update check compares version numbers. Official Xtra offers a release
 * whenever its APK was uploaded after the last check, so a sideloaded version (or a check while
 * the next build was still being made) re-offered the same version. Now a release is offered
 * only when its version (tag "xtra5s-vX.Y.Z") is newer than the installed one; releases without
 * a readable version fall back to the upload time.
 * Upstream hooks: the update checks in MainViewModel and SettingsViewModel, and the "no updates"
 * message in SettingsActivity.
 */
object UpdateCheck {

    /** "xtra5s-v1.66.0", "v1.66.0", "1.66.0" -> [1, 66, 0]; null if it isn't a version. */
    internal fun parse(text: String?): List<Int>? {
        val match = Regex("""(\d+)\.(\d+)\.(\d+)""").find(text ?: return null) ?: return null
        return match.groupValues.drop(1).map { it.toInt() }
    }

    internal fun isNewer(release: String?, installed: String?): Boolean? {
        val r = parse(release) ?: return null
        val i = parse(installed) ?: return null
        for (k in 0..2) if (r[k] != i[k]) return r[k] > i[k]
        return false
    }

    /** Whether to offer [response]'s release: newer version, else (no version) [uploadedSinceCheck]. */
    fun offer(response: JsonObject, uploadedSinceCheck: Boolean): Boolean {
        val tag = (response["tag_name"] as? JsonPrimitive)?.contentOrNull ?: (response["name"] as? JsonPrimitive)?.contentOrNull
        return isNewer(tag, BuildConfig.VERSION_NAME) ?: uploadedSinceCheck
    }

    /** Settings > Check for updates, when there's nothing newer. */
    fun noUpdateMessage(context: Context): String =
        "You're on the latest version (${if (CompanionApp.isCompanion(context)) CompanionApp.NAME else "Xtra5s"} ${BuildConfig.VERSION_NAME})"
}
