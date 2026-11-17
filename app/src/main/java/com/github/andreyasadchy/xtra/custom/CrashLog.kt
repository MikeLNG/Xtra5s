package com.github.andreyasadchy.xtra.custom

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.github.andreyasadchy.xtra.util.getAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Xtra5s custom: when the app crashes, the error is saved to a text file in Download
 * ("Xtra5s-crash-log.txt", or "Xtra5s-Companion-crash-log.txt" for Companion), newest crash
 * first, last [MAX_ENTRIES] crashes kept. The next time the app opens, a popup says it crashed,
 * with Share to send the error text.
 *
 * Started by [Starter] (a provider in the manifest, so it runs as the app starts, before
 * anything else), so no upstream code hook is needed for catching crashes.
 */
object CrashLog {

    internal const val SEPARATOR = "========================================"
    internal const val MAX_ENTRIES = 20
    private const val PENDING = "xtra5s-last-crash.txt"
    private const val LOG = "xtra5s-crash-log.txt"

    fun fileName(context: Context) = if (CompanionApp.isCompanion(context)) "Xtra5s-Companion-crash-log.txt" else "Xtra5s-crash-log.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext ?: context
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                save(app, entry(app, thread, error))
            } catch (_: Throwable) {}
            previous?.uncaughtException(thread, error)
        }
    }

    private fun entry(context: Context, thread: Thread, error: Throwable): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) { "?" }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        return buildString {
            append(time).append('\n')
            append(if (CompanionApp.isCompanion(context)) CompanionApp.NAME else "Xtra5s").append(' ').append(version).append('\n')
            append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append("), ")
            append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Thread: ").append(thread.name).append('\n').append('\n')
            append(Log.getStackTraceString(error).trim()).append('\n')
        }
    }

    /** The log with [entry] put first, keeping at most [max] crashes. */
    internal fun withEntry(log: String?, entry: String, max: Int = MAX_ENTRIES): String {
        val old = log.orEmpty().split("$SEPARATOR\n").map { it.trim() }.filter { it.isNotEmpty() }
        return (listOf(entry.trim()) + old).take(max).joinToString("") { "$SEPARATOR\n$it\n" }
    }

    private fun save(context: Context, entry: String) {
        File(context.filesDir, PENDING).writeText(entry)
        val logFile = File(context.filesDir, LOG)
        val log = withEntry(if (logFile.exists()) logFile.readText() else null, entry)
        logFile.writeText(log)
        try {
            writeToDownloads(context, log)
        } catch (_: Throwable) {}
    }

    /** Puts the whole log in Download (Android 10+; older versions keep it inside the app). */
    private fun writeToDownloads(context: Context, text: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val name = fileName(context)
        val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"), null
        )?.use { cursor ->
            if (cursor.moveToFirst()) android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)) else null
        }
        val uri = existing ?: resolver.insert(collection, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
        }) ?: return
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
    }

    /** After a crash: a popup saying so, with Share for the error text. Shown once. */
    fun onMainActivityCreate(activity: AppCompatActivity) {
        install(activity)
        val pending = File(activity.filesDir, PENDING)
        if (!pending.exists()) return
        val text = try { pending.readText() } catch (_: Exception) { "" }
        pending.delete()
        if (text.isBlank()) return
        val app = if (CompanionApp.isCompanion(activity)) CompanionApp.NAME else "Xtra5s"
        val where = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "\n\nAll recent crashes are saved in Download/${fileName(activity)}." else ""
        try {
            activity.getAlertDialogBuilder()
                .setTitle("$app crashed last time")
                .setMessage("The error was saved.$where")
                .setPositiveButton("Share") { _, _ ->
                    try {
                        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "$app crash")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }, "Share crash"))
                    } catch (_: Exception) {}
                }
                .setNegativeButton("Close", null)
                .show()
        } catch (_: Exception) {}
    }

    /** Starts [CrashLog] as the app starts (declared in the manifest). */
    class Starter : ContentProvider() {
        override fun onCreate(): Boolean {
            context?.let { install(it) }
            return true
        }

        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
