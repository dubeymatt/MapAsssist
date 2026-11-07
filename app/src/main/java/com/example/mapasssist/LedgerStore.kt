package com.example.mapasssist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class LedgerAssignment(
    val mapNo: String,
    val cardTitle: String,
    val assignedTo: String,
    val assignedOn: String,
    val returnedOn: String = "",
    val notes: String = ""
) {
    /** Blank placeholder records preserve unassigned Ledger-only business territories. */
    val active get() = assignedTo.isNotBlank() && returnedOn.isBlank()
}

/** Local assignment history. Map names remain sourced from the current DNC cards. */
object LedgerStore {
    private const val KEY = "ledger_assignments"
    private const val MODIFIED = "ledger_last_modified"

    fun read(context: Context): List<LedgerAssignment> = runCatching {
        val array = JSONArray(context.getSharedPreferences("map_assist", 0).getString(KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val value = array.getJSONObject(index)
                add(LedgerAssignment(
                    value.optString("mapNo"), value.optString("title"), value.optString("assignedTo"),
                    value.optString("assignedOn"), value.optString("returnedOn"), value.optString("notes")
                ))
            }
        }
    }.getOrDefault(emptyList())

    fun replace(context: Context, assignments: List<LedgerAssignment>, notifySharedSync: Boolean = true) {
        val json = JSONArray()
        assignments.forEach { assignment ->
            json.put(JSONObject().apply {
                put("mapNo", assignment.mapNo); put("title", assignment.cardTitle)
                put("assignedTo", assignment.assignedTo); put("assignedOn", assignment.assignedOn)
                put("returnedOn", assignment.returnedOn); put("notes", assignment.notes)
            })
        }
        context.getSharedPreferences("map_assist", 0).edit()
            .putString(KEY, json.toString()).putLong(MODIFIED, System.currentTimeMillis()).apply()
        if (notifySharedSync) GoogleDriveBackup.queueAutomaticSharedSync(context)
    }

    fun lastModified(context: Context) = context.getSharedPreferences("map_assist", 0).getLong(MODIFIED, 0L)
}
