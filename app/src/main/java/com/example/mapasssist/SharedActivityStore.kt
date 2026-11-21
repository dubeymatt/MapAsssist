package com.example.mapasssist

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import org.json.JSONArray
import org.json.JSONObject

data class SharedActivity(val time: Long, val person: String, val summary: String)

/** Portable audit history that is included in the shared Map Assist CSV. */
object SharedActivityStore {
    private const val PREFS = "map_assist"
    private const val KEY = "shared_activity_history"
    private const val LAST_SEEN = "shared_activity_last_seen"
    private const val INCOMING = "shared_activity_incoming"
    private const val MAX_ENTRIES = 120

    fun read(context: Context): List<SharedActivity> = runCatching {
        val values = JSONArray(context.getSharedPreferences(PREFS, 0).getString(KEY, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(SharedActivity(item.optLong("time"), item.optString("person"), item.optString("summary")))
            }
        }
    }.getOrDefault(emptyList()).sortedByDescending { it.time }

    fun record(context: Context, summary: String) {
        if (!FirebaseSharedLibrary.isConfigured(context)) return
        val account = GoogleSignIn.getLastSignedInAccount(context)
        val person = account?.displayName?.takeIf { it.isNotBlank() }
            ?: account?.email?.takeIf { it.isNotBlank() }
            ?: "This device"
        val entry = SharedActivity(System.currentTimeMillis(), person, summary)
        val entries = (read(context) + entry).sortedByDescending { it.time }.take(MAX_ENTRIES)
        save(context, entries, entries.maxOfOrNull { it.time } ?: 0L)
        FirebaseSharedLibrary.recordActivity(context, entry)
    }

    /** Imports remote history and exposes only entries not previously seen here. */
    fun replaceFromRemote(context: Context, entries: List<SharedActivity>) {
        val previousSeen = context.getSharedPreferences(PREFS, 0).getLong(LAST_SEEN, 0L)
        val ordered = entries.sortedByDescending { it.time }.take(MAX_ENTRIES)
        val incoming = ordered.filter { it.time > previousSeen }
        val newest = ordered.maxOfOrNull { it.time } ?: previousSeen
        save(context, ordered, newest)
        if (incoming.isNotEmpty()) {
            context.getSharedPreferences(PREFS, 0).edit()
                .putString(INCOMING, encode(incoming))
                .apply()
        }
    }

    fun consumeIncoming(context: Context): List<SharedActivity> {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val entries = decode(prefs.getString(INCOMING, "[]").orEmpty())
        prefs.edit().remove(INCOMING).apply()
        return entries
    }

    fun hasIncoming(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).contains(INCOMING)

    private fun save(context: Context, entries: List<SharedActivity>, lastSeen: Long) {
        context.getSharedPreferences(PREFS, 0).edit()
            .putString(KEY, encode(entries))
            .putLong(LAST_SEEN, lastSeen)
            .apply()
    }

    private fun encode(entries: List<SharedActivity>) = JSONArray().apply {
        entries.forEach { put(JSONObject().put("time", it.time).put("person", it.person).put("summary", it.summary)) }
    }.toString()

    private fun decode(value: String): List<SharedActivity> = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(SharedActivity(item.optLong("time"), item.optString("person"), item.optString("summary")))
            }
        }
    }.getOrDefault(emptyList())
}
