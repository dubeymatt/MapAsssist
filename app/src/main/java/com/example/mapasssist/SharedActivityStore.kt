package com.example.mapasssist

import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import org.json.JSONArray
import org.json.JSONObject

data class SharedActivity(
    val time: Long,
    val person: String,
    val summary: String,
    val kind: String = "",
    val mapNo: String = "",
    val title: String = ""
)

/** Portable audit history that is included in the shared Map Assist CSV. */
object SharedActivityStore {
    private const val PREFS = "map_assist"
    private const val KEY = "shared_activity_history"
    private const val LAST_SEEN = "shared_activity_last_seen"
    private const val INCOMING = "shared_activity_incoming"
    private const val CLEARED_AT = "shared_activity_cleared_at"
    private const val MAX_ENTRIES = 120
    const val KIND_DNC = "dnc"
    const val KIND_LEDGER = "ledger"

    fun read(context: Context): List<SharedActivity> = runCatching {
        val values = JSONArray(context.getSharedPreferences(PREFS, 0).getString(KEY, "[]"))
        buildList {
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index)
                add(activityFromJson(item))
            }
        }
    }.getOrDefault(emptyList()).filter { it.time > context.getSharedPreferences(PREFS, 0).getLong(CLEARED_AT, 0L) }.sortedByDescending { it.time }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, 0).edit().putLong(CLEARED_AT, System.currentTimeMillis()).remove(INCOMING).apply()
    }

    fun record(context: Context, summary: String) {
        record(context, SharedActivity(System.currentTimeMillis(), person(context), summary))
    }

    fun recordDnc(context: Context, action: String, mapNo: String, title: String) {
        val label = cardLabel(mapNo, title)
        record(context, SharedActivity(
            time = System.currentTimeMillis(), person = person(context),
            summary = "$label · DNC $action", kind = KIND_DNC, mapNo = mapNo, title = title
        ))
    }

    fun recordLedger(context: Context, action: String, mapNo: String, title: String) {
        val label = title.trim().ifBlank { mapNo.trim().ifBlank { "Map" } }
        record(context, SharedActivity(
            time = System.currentTimeMillis(), person = person(context),
            summary = "$label · Ledger entry $action", kind = KIND_LEDGER, mapNo = mapNo, title = title
        ))
    }

    private fun record(context: Context, entry: SharedActivity) {
        if (!FirebaseSharedLibrary.isConfigured(context)) return
        val entries = (read(context) + entry).sortedByDescending { it.time }.take(MAX_ENTRIES)
        save(context, entries, entries.maxOfOrNull { it.time } ?: 0L)
        FirebaseSharedLibrary.recordActivity(context, entry)
    }

    /** Imports remote history and exposes only entries not previously seen here. */
    fun replaceFromRemote(context: Context, entries: List<SharedActivity>) {
        val preferences = context.getSharedPreferences(PREFS, 0)
        val previousSeen = maxOf(preferences.getLong(LAST_SEEN, 0L), preferences.getLong(CLEARED_AT, 0L))
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
        entries.forEach {
            put(JSONObject()
                .put("time", it.time)
                .put("person", it.person)
                .put("summary", it.summary)
                .put("kind", it.kind)
                .put("mapNo", it.mapNo)
                .put("title", it.title)
            )
        }
    }.toString()

    private fun decode(value: String): List<SharedActivity> = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(activityFromJson(item))
            }
        }
    }.getOrDefault(emptyList())

    private fun activityFromJson(item: JSONObject) = SharedActivity(
        time = item.optLong("time"),
        person = item.optString("person"),
        summary = item.optString("summary"),
        kind = item.optString("kind"),
        mapNo = item.optString("mapNo"),
        title = item.optString("title")
    )

    private fun person(context: Context): String {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        return account?.displayName?.takeIf { it.isNotBlank() }
            ?: account?.email?.takeIf { it.isNotBlank() }
            ?: "This device"
    }

    private fun cardLabel(mapNo: String, title: String): String = title.trim().ifBlank { mapNo.ifBlank { "DNC" } }
}
