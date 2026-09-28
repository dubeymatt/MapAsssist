package com.example.mapasssist

import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile

object BackupCsv {
    fun create(files: List<TextFile>): String = buildString {
        appendLine("DNC Card,Date (MM/YY),Address,Supporting information")
        files.sortedWith(
            compareBy<TextFile> { mapNumberForSort(it.mapNo) }
                .thenBy { it.mapNo.lowercase() }
                .thenBy { it.title.lowercase() }
        ).forEach { file ->
            val rows = DncEntryCodec.decode(file.entriesJson)
                .ifEmpty { listOf(DncEntry(supportingInformation = file.content)) }
            rows.forEachIndexed { index, row ->
                appendLine(
                    listOf(
                        if (index == 0) "${file.mapNo} - ${file.title}" else "",
                        normalizeDate(row.date).replace('/', '.'),
                        row.address,
                        row.supportingInformation
                    ).joinToString(",") { csv(it) }
                )
            }
            appendLine()
        }
    }

    /** Parses the same portable CSV format used for local and Drive backups. */
    fun parse(text: String): List<TextFile> {
        val grouped = linkedMapOf<Pair<String, String>, MutableList<DncEntry>>()
        var active: Pair<String, String>? = null
        parseRecords(text).drop(1).forEach { columns ->
            if (columns.isNotEmpty() && columns.any { it.isNotBlank() }) {
                val first = columns.getOrElse(0) { "" }.trim()
                if (first.isNotEmpty()) {
                    val split = first.split(" - ", limit = 2)
                    require(split.size == 2) { "Invalid DNC Card" }
                    active = split[1] to split[0]
                }
                val key = active ?: error("Missing DNC Card")
                grouped.getOrPut(key) { mutableListOf() } += DncEntry(
                    importedDate(columns.getOrElse(1) { "" }),
                    columns.getOrElse(2) { "" },
                    columns.getOrElse(3) { "" }
                )
            }
        }
        return grouped.map { (key, rows) ->
            TextFile(title = key.first, mapNo = key.second, entriesJson = DncEntryCodec.encode(rows))
        }
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun normalizeDate(value: String): String {
        val match = Regex("(0?[1-9]|1[0-2])[/.-](\\d{2,4})").find(value) ?: return value
        return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].takeLast(2).toInt())
    }

    private fun importedDate(value: String): String {
        val text = value.trim()
        val date = if (text.startsWith("=\"") && text.endsWith("\"") && text.length >= 4) text.substring(2, text.length - 1) else text
        return date.replace('.', '/').take(5)
    }

    private fun mapNumberForSort(value: String): Int {
        return Regex("\\d+").find(value)?.value?.toIntOrNull() ?: Int.MAX_VALUE
    }

    private fun parseRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            when (val character = text[index]) {
                '"' -> if (quoted && index + 1 < text.length && text[index + 1] == '"') { cell.append(character); index++ } else quoted = !quoted
                ',' -> if (quoted) cell.append(character) else { row += cell.toString(); cell.clear() }
                '\n', '\r' -> if (quoted) cell.append(character) else {
                    if (character == '\r' && index + 1 < text.length && text[index + 1] == '\n') index++
                    row += cell.toString(); cell.clear(); records += row; row = mutableListOf()
                }
                else -> cell.append(character)
            }
            index++
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); records += row }
        return records
    }
}
