package com.example.mapasssist

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.GridLayoutManager
import android.widget.PopupMenu
import android.widget.EditText
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TextFileViewModel by viewModels()
    private var sortAlphabetically = false
    private var sortNumerically = false
    private var currentFiles = emptyList<TextFile>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val preferences = requireContext().getSharedPreferences("map_assist", 0)
        val adapter = TextFileListAdapter({ textFile ->
            val bundle = Bundle().apply {
                putInt("textFileId", textFile.id)
            }
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment, bundle)
        }, { viewModel.update(it) }, { viewModel.delete(it) })
        binding.recyclerview.adapter = adapter
        val savedGrid = preferences.getBoolean("grid_view", false)
        binding.viewToggle.check(if (savedGrid) R.id.grid_view_button else R.id.list_view_button)
        binding.recyclerview.layoutManager = if (savedGrid) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
        adapter.setGridMode(savedGrid)

        binding.viewToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val grid = checkedId == R.id.grid_view_button
            preferences.edit().putBoolean("grid_view", grid).apply()
            binding.recyclerview.layoutManager = if (grid) GridLayoutManager(requireContext(), 2) else LinearLayoutManager(requireContext())
            adapter.setGridMode(grid)
        }

        binding.editCardsButton.setOnClickListener {
            if (adapter.editMode) { adapter.editMode = false; configureAddButton(adapter, false) }
            else android.app.AlertDialog.Builder(requireContext()).setTitle("Enable edit mode?").setMessage("Renaming or deleting DNC cards makes permanent changes to your saved data.").setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ -> adapter.editMode = true; configureAddButton(adapter, true) }.show()
        }

        configureAddButton(adapter, false)

        binding.filterButton.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add(0, 1, 0, "Date edited")
                menu.add(0, 2, 1, "Alphabetically")
                menu.add(0, 3, 2, "Numerically by Map No.")
                setOnMenuItemClickListener { item ->
                    sortAlphabetically = item.itemId == 2
                    sortNumerically = item.itemId == 3
                    submitSorted(adapter)
                    true
                }
                show()
            }
        }

        viewModel.allTextFiles.observe(viewLifecycleOwner) { files ->
            currentFiles = files
            submitSorted(adapter)
            binding.emptyView.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun configureAddButton(adapter: TextFileListAdapter, editing: Boolean) {
        val button = requireActivity().findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.add_fab)
        button.text = if (editing) "Add DNC Card" else "Add"
        button.setOnClickListener {
            if (editing) {
                val title = EditText(requireContext()).apply { hint = "DNC title" }; val number = EditText(requireContext()).apply { hint = "Map No." }
                val form = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(number); addView(title) }
                android.app.AlertDialog.Builder(requireContext()).setTitle("Add DNC Card").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ -> if (title.text.isNotBlank()) viewModel.insert(com.example.mapasssist.data.TextFile(title = title.text.toString(), mapNo = number.text.toString())) }.show()
            } else {
                if (currentFiles.isEmpty()) return@setOnClickListener
                val labels = currentFiles.map { "${it.mapNo} - ${it.title}" }.toTypedArray()
                android.app.AlertDialog.Builder(requireContext()).setTitle("Add entry to DNC card").setItems(labels) { _, which -> quickAdd(currentFiles[which]) }.show()
            }
        }
    }

    private fun quickAdd(file: com.example.mapasssist.data.TextFile) {
        val date = EditText(requireContext()).apply { hint = "Date (MM/YY)" }; val address = EditText(requireContext()).apply { hint = "Address" }; val info = EditText(requireContext()).apply { hint = "Supporting information" }
        val form = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(date); addView(address); addView(info) }
        android.app.AlertDialog.Builder(requireContext()).setTitle("Add to ${file.mapNo} - ${file.title}").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
            if (date.text.isBlank() || date.text.toString().matches(Regex("(0[1-9]|1[0-2])/\\d{2}"))) { val entries = com.example.mapasssist.data.DncEntryCodec.decode(file.entriesJson).toMutableList(); entries += com.example.mapasssist.data.DncEntry(date.text.toString(), address.text.toString(), info.text.toString()); viewModel.update(file.copy(entriesJson = com.example.mapasssist.data.DncEntryCodec.encode(entries), lastModified = System.currentTimeMillis())) }
        }.show()
    }

    private fun submitSorted(adapter: TextFileListAdapter) {
        val sorted = when {
            sortAlphabetically -> currentFiles.sortedBy { it.title.lowercase() }
            sortNumerically -> currentFiles.sortedWith(
                compareBy<TextFile> {
                    it.mapNo.substringBefore(" ").toIntOrNull() ?: Int.MAX_VALUE
                }.thenBy { it.mapNo }
            )
            else -> currentFiles.sortedByDescending { it.lastModified }
        }
        adapter.submitList(sorted)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
