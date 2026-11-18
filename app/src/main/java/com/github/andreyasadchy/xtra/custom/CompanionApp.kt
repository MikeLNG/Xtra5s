package com.github.andreyasadchy.xtra.custom

import android.content.Context

/**
 * Xtra5s custom: Xtra5s Companion is the same app built under another package
 * (`-Pxtra5sCompanion`), so it installs next to Xtra5s as a separate app with its own settings
 * and login. Since Xtra5s 1.60.0 the two apps don't talk to each other at all.
 * The only thing here: each app's updater installs its own APK from a release.
 */
object CompanionApp {

    const val COMPANION_PACKAGE = "com.prime385.xtra5s.companion"
    const val NAME = "Xtra5s Companion"

    fun isCompanion(context: Context) = context.packageName == COMPANION_PACKAGE

    /** For the updater: true when a release's APK named [assetName] is this app's own. */
    fun isMyUpdate(context: Context, assetName: String?): Boolean = isMyUpdate(isCompanion(context), assetName)

    internal fun isMyUpdate(companion: Boolean, assetName: String?): Boolean {
        val name = assetName ?: return true
        val companionApk = name.contains("Companion", true)
        return if (companion) companionApk else !companionApk
    }
}
