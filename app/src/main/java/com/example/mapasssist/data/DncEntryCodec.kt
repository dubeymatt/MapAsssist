package com.example.mapasssist.data

import org.json.JSONArray
import org.json.JSONObject

object DncEntryCodec {
    fun encode(entries: List<DncEntry>): String = JSONArray().apply {
        entries.forEach { entry -> put(JSONObject().apply {
            put("date", entry.date)
            put("address", entry.address)
            put("supportingInformation", entry.supportingInformation)
        }) }
    }.toString()

    fun decode(value: String): List<DncEntry> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> array.getJSONObject(index).let {
            DncEntry(it.optString("date"), it.optString("address"), it.optString("supportingInformation"))
        } }
    }.getOrDefault(emptyList())
}
