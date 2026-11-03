package com.example.mapasssist

import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile

object BackupCsv {
    data class BackupData(val files: List<TextFile>, val assignments: List<LedgerAssignment>)

    fun create(files: List<TextFile>, assignments: List<LedgerAssignment> = emptyList()): String = buildString {
        appendLine("DNC Card,Date (MM/YY),Address,Supporting information")
        files.sortedWith(Comparator { first, second ->
            MapNumber.compare(first.mapNo, second.mapNo)
                .takeIf { it != 0 }
                ?: first.title.compareTo(second.title, ignoreCase = true)
        }).forEach { file ->
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
        if (assignments.isNotEmpty()) {
            appendLine("Ledger DNC Card,Assigned to,Assigned on,Completed on,Notes")
            var previousCard: Pair<String, String>? = null
            assignments.sortedWith(Comparator { first, second ->
                val businessOrder = MapGroupAccent.isBusinessMap(first.mapNo).compareTo(MapGroupAccent.isBusinessMap(second.mapNo))
                businessOrder.takeIf { it != 0 }
                    ?: MapNumber.compare(first.mapNo, second.mapNo)
                    .takeIf { it != 0 }
                    ?: first.cardTitle.compareTo(second.cardTitle, ignoreCase = true)
                        .takeIf { it != 0 }
                    ?: ledgerDateSortKey(first.assignedOn).compareTo(ledgerDateSortKey(second.assignedOn))
            }).forEach { assignment ->
                val card = assignment.mapNo to assignment.cardTitle
                if (previousCard != null && previousCard != card) appendLine()
                appendLine(listOf(
                    if (previousCard == card) "" else "${assignment.mapNo} - ${assignment.cardTitle}", assignment.assignedTo,
                    assignment.assignedOn, assignment.returnedOn, assignment.notes
                ).joinToString(",") { csv(it) })
                previousCard = card
            }
        }
    }

    /** Parses the same portable CSV format used for local and Drive backups. */
    fun parse(text: String): List<TextFile> = parseBundle(text).files

    fun parseBundle(text: String): BackupData {
        val grouped = linkedMapOf<Pair<String, String>, MutableList<DncEntry>>()
        val assignments = mutableListOf<LedgerAssignment>()
        var active: Pair<String, String>? = null
        var activeLedger: Pair<String, String>? = null
        var readingLedger = false
        var ledgerUsesCombinedCards = false
        parseRecords(text).drop(1).forEach { columns ->
            if (columns.isNotEmpty() && columns.any { it.isNotBlank() }) {
                if (columns.firstOrNull()?.trim()?.equals("Ledger DNC Card", ignoreCase = true) == true ||
                    columns.firstOrNull()?.trim()?.equals("Ledger map number", ignoreCase = true) == true) {
                    readingLedger = true
                    ledgerUsesCombinedCards = columns.firstOrNull()?.trim()?.equals("Ledger DNC Card", ignoreCase = true) == true
                    active = null
                    activeLedger = null
                    return@forEach
                }
                if (readingLedger) {
                    val card = columns.getOrElse(0) { "" }.trim()
                    if (ledgerUsesCombinedCards) {
                        if (card.isNotEmpty()) {
                            val combined = card.split(" - ", limit = 2)
                            require(combined.size == 2) { "Invalid Ledger DNC Card" }
                            activeLedger = combined[0] to combined[1]
                        }
                        val ledgerCard = activeLedger ?: return@forEach
                        assignments += LedgerAssignment(
                            ledgerCard.first, ledgerCard.second,
                            columns.getOrElse(1) { "" }, columns.getOrElse(2) { "" },
                            columns.getOrElse(3) { "" }, columns.getOrElse(4) { "" }
                        )
                    } else if (card.isNotEmpty()) {
                        val combined = card.split(" - ", limit = 2)
                        assignments += LedgerAssignment(
                            combined[0], columns.getOrElse(1) { "" },
                            columns.getOrElse(2) { "" }, columns.getOrElse(3) { "" },
                            columns.getOrElse(4) { "" }, columns.getOrElse(5) { "" }
                        )
                    }
                    return@forEach
                }
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
        return BackupData(grouped.map { (key, rows) ->
            TextFile(title = key.first, mapNo = key.second, entriesJson = DncEntryCodec.encode(rows))
        }, assignments)
    }

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun normalizeDate(value: String): String {
        val match = Regex("(0?[1-9]|1[0-2])[/.-](\\d{2,4})").find(value) ?: return value
        return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].takeLast(2).toInt())
    }

    private fun ledgerDateSortKey(value: String): String {
        val parts = value.trim().split('/')
        return if (parts.size == 3) "${parts[2].padStart(4, '0')}${parts[1].padStart(2, '0')}${parts[0].padStart(2, '0')}" else value
    }

    private fun importedDate(value: String): String {
        val text = value.trim()
        val date = if (text.startsWith("=\"") && text.endsWith("\"") && text.length >= 4) text.substring(2, text.length - 1) else text
        return date.replace('.', '/').take(5)
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
