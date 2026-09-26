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
import androidx.appcompat.app.AppCompatDelegate
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentSettingsBinding
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
                writer.appendLine("DNC Card,Date (MM/YY),Address,Supporting information")
                files.sortedBy { it.mapNo.lowercase() }.forEach { file ->
                    val rows = DncEntryCodec.decode(file.entriesJson).ifEmpty { listOf(DncEntry(supportingInformation = file.content)) }
                    rows.forEachIndexed { index, row ->
                        writer.appendLine(listOf(if (index == 0) "${file.mapNo} - ${file.title}" else "", excelTextDate(row.date), row.address, row.supportingInformation).joinToString(",") { csv(it) })
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
        val preferences = requireContext().getSharedPreferences("map_assist", 0)
        binding.darkModeSwitch.isChecked = preferences.getBoolean("dark_mode", false)
        binding.darkModeSwitch.setOnCheckedChangeListener { _, enabled -> preferences.edit().putBoolean("dark_mode", enabled).apply(); AppCompatDelegate.setDefaultNightMode(if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO) }
        binding.openDownloadsButton.setOnClickListener {
            val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            runCatching { startActivity(intent) }.getOrElse {
                Toast.makeText(requireContext(), "Could not open the Files app", Toast.LENGTH_SHORT).show()
            }
        }
        viewModel.allTextFiles.observe(viewLifecycleOwner) { files = it; binding.exportButton.isEnabled = it.isNotEmpty() }
        binding.exportButton.setOnClickListener {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
            exportFile.launch("map_assist_dncs_$date.csv")
        }
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
                grouped.getOrPut(key) { mutableListOf() } += DncEntry(importedDate(columns.getOrElse(1) { "" }), columns.getOrElse(2) { "" }, columns.getOrElse(3) { "" })
            }
        } } ?: error("No data")
        return grouped.map { (key, rows) -> TextFile(title = key.first, mapNo = key.second, entriesJson = DncEntryCodec.encode(rows)) }
    }
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun normalizeDate(value: String): String { val match = Regex("(0?[1-9]|1[0-2])[/.-](\\d{2,4})").find(value) ?: return value; return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].takeLast(2).toInt()) }
    private fun excelTextDate(value: String) = normalizeDate(value).replace('/', '.')
    private fun importedDate(value: String): String { val text = value.trim(); val date = if (text.startsWith("=\"") && text.endsWith("\"") && text.length >= 4) text.substring(2, text.length - 1) else text; return date.replace('.', '/').take(5) }
    private fun parseCsvRecords(text: String): List<List<String>> { val records = mutableListOf<List<String>>(); var row = mutableListOf<String>(); val cell = StringBuilder(); var quote = false; var i = 0; while (i < text.length) { val c = text[i]; when { c == '"' && quote && i + 1 < text.length && text[i + 1] == '"' -> { cell.append(c); i++ }; c == '"' -> quote = !quote; c == ',' && !quote -> { row += cell.toString(); cell.clear() }; (c == '\n' || c == '\r') && !quote -> { if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++; row += cell.toString(); cell.clear(); records += row; row = mutableListOf() }; else -> cell.append(c) }; i++ }; if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); records += row }; return records }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
