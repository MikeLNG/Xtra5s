package com.github.andreyasadchy.xtra.custom

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription

/**
 * Xtra5s custom: the chat's own copy of its messages ("the screen list").
 *
 * Official Xtra lets the chat list read straight from the view model's message list, which the
 * chat connection fills on a background thread. The screen is told "one message added" a moment
 * later on the main thread, so in a busy chat (and more so with two chats in Dual Stream) more
 * messages can already be in the list than the screen has been told about. The list on screen
 * and its data then disagree: rows show the wrong messages (earlier ones further down), the chat
 * jumps, and it loses the newest message.
 *
 * Now the chat list shows the screen list, changed only on the main thread and only by the
 * "added / history loaded / cleared" notices, in order, so the two always agree. Each time the
 * chat (re)starts receiving messages, the screen list is copied afresh from the view model's.
 * Scrolling is unchanged (official Xtra's own rules).
 *
 * Kept in its own package so upstream Xtra updates can be merged without conflicts.
 * Upstream hooks: ChatFragment (the screen list, its message notices, and the places that look
 * a message up by position).
 */
object ChatScreen {

    /** How far back from the end a just-delivered message may already be (copied by a resync). */
    private const val RECENT = 8

    fun <T> newList(): ArrayList<T> = ArrayList()

    /** Copies [source] (the view model's list) into [screen]. */
    fun <T> resync(screen: MutableList<T>, source: MutableList<T>) {
        val copy = synchronized(source) { ArrayList(source) }
        screen.clear()
        screen.addAll(copy)
    }

    /**
     * A new message (and how many old ones the chat drops from the top for it). Returns where it
     * was added (-1 if a resync had already copied it) and how many were dropped.
     */
    fun <T> onNew(screen: MutableList<T>, message: T, removeCount: Int): Pair<Int, Int> {
        val already = (screen.lastIndex downTo maxOf(0, screen.size - RECENT)).any { screen[it] === message }
        var added = -1
        if (!already) {
            screen.add(message)
            added = screen.lastIndex
        }
        val removed = removeCount.coerceIn(0, screen.size)
        repeat(removed) { screen.removeAt(0) }
        return added to removed
    }

    /** Older messages loaded when joining, put at the top. False if a resync had already copied them. */
    fun <T> onHistory(screen: MutableList<T>, messages: List<T>): Boolean {
        if (messages.isEmpty() || (screen.isNotEmpty() && screen[0] === messages[0])) return false
        screen.addAll(0, messages)
        return true
    }

    /** The chat was cleared (e.g. disconnected). Returns how many rows went. */
    fun <T> onClear(screen: MutableList<T>): Int {
        val count = screen.size
        screen.clear()
        return count
    }

    /** [flow], running [onStart] each time the chat starts receiving it (before the first message). */
    fun <T> subscribed(flow: SharedFlow<T>, onStart: suspend () -> Unit): Flow<T> = flow.onSubscription { onStart() }
}
