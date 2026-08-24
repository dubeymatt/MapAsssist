package com.example.mapasssist

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentSettingsBinding
import java.io.BufferedReader
import java.io.OutputStreamWriter

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TextFileViewModel by viewModels()
    private var files = emptyList<TextFile>()
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri ?: return@registerForActivityResult
        requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
            OutputStreamWriter(stream).use { writer ->
                writer.appendLine("DNC Card (Map No. - Title),Date (MM/YY),Address,Supporting information")
                files.sortedBy { it.mapNo.lowercase() }.forEach { file ->
                    val rows = DncEntryCodec.decode(file.entriesJson).ifEmpty { listOf(DncEntry(supportingInformation = file.content)) }
                    rows.forEachIndexed { index, row ->
                        writer.appendLine(listOf(if (index == 0) "${file.mapNo} - ${file.title}" else "", row.date.take(5), row.address, row.supportingInformation).joinToString(",") { csv(it) })
                    }
                    writer.appendLine()
                }
            }
        }
        Toast.makeText(requireContext(), "Spreadsheet exported", Toast.LENGTH_SHORT).show()
    }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val imported = runCatching { readCsv(uri) }.getOrElse { Toast.makeText(requireContext(), "Could not read this spreadsheet. Export a fresh Map Assist spreadsheet and use that as the template.", Toast.LENGTH_LONG).show(); return@registerForActivityResult }
        AlertDialog.Builder(requireContext()).setTitle("Import will permanently replace all data").setMessage("This will delete all ${files.size} saved DNC cards and replace them with ${imported.size} cards from the spreadsheet. This cannot be undone.")
            .setNegativeButton("Cancel", null).setPositiveButton("Replace all data") { _, _ -> viewModel.replaceAll(imported); Toast.makeText(requireContext(), "DNCs replaced", Toast.LENGTH_SHORT).show() }.show()
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View { _binding = FragmentSettingsBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.allTextFiles.observe(viewLifecycleOwner) { files = it; binding.exportButton.isEnabled = it.isNotEmpty() }
        binding.exportButton.setOnClickListener { exportFile.launch("map_assist_dncs.csv") }
        binding.importButton.setOnClickListener { importFile.launch(arrayOf("text/csv", "text/comma-separated-values", "application/vnd.ms-excel")) }
    }
    private fun readCsv(uri: android.net.Uri): List<TextFile> {
        val grouped = linkedMapOf<Pair<String, String>, MutableList<DncEntry>>(); var active: Pair<String, String>? = null
        requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
            val rows = parseCsvRecords(reader.readText()).drop(1)
            rows.forEach { columns -> if (columns.isNotEmpty() && columns.any { it.isNotBlank() }) {
                val first = columns.getOrElse(0) { "" }.trim()
                if (first.isNotEmpty()) { val split = first.split(" - ", limit = 2); require(split.size == 2) { "Invalid DNC Card" }; active = split[1] to split[0] }
                val key = active ?: error("Missing DNC Card")
                grouped.getOrPut(key) { mutableListOf() } += DncEntry(columns.getOrElse(1) { "" }.take(5), columns.getOrElse(2) { "" }, columns.getOrElse(3) { "" })
            }
        } } ?: error("No data")
        return grouped.map { (key, rows) -> TextFile(title = key.first, mapNo = key.second, entriesJson = DncEntryCodec.encode(rows)) }
    }
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun parseCsvRecords(text: String): List<List<String>> { val records = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quote = false; var i = 0; while (i < text.length) { val c = text[i]; when { c == '"' && quote && i + 1 < text.length && text[i + 1] == '"' -> { cell.append(c); i++ }; c == '"' -> quote = !quote; c == ',' && !quote -> { row += cell.toString(); cell.clear() }; (c == '\n' || c == '\r') && !quote -> { if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; row += cell.toString(); cell.clear(); records += row; row = mutableListOf() }; else -> cell.append(c) }; i++ }; if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); records += row }; return records }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
