package com.example.mapasssist.data

import org.json.JSONArray
import org.json.JSONObject

object DncEntryCodec {
    fun encode(entries: List<DncEntry>): String = JSONArray().apply {
        entries.forEach { entry -> put(JSONObject().apply {
            put("date", normalizeDate(entry.date))
            put("address", entry.address)
            put("supportingInformation", entry.supportingInformation)
        }) }
    }.toString()

    fun decode(value: String): List<DncEntry> = runCatching {
        val array = JSONArray(value)
        List(array.length()) { index -> array.getJSONObject(index).let {
            DncEntry(normalizeDate(it.optString("date")), it.optString("address"), it.optString("supportingInformation"))
        } }
    }.getOrDefault(emptyList())

    private fun normalizeDate(value: String): String {
        val date = value.replace('.', '/')
        val match = Regex("(0?[1-9]|1[0-2])/(\\d{2,4})").find(date) ?: return date
        return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].takeLast(2).toInt())
    }
}
