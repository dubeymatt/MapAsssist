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
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SearchView
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentFirstBinding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TextFileViewModel by viewModels()
    private var sortAlphabetically = false
    private var sortNumerically = false
    private var currentFiles = emptyList<TextFile>()
    private var searchQuery = ""
    private val stagedUpdates = mutableMapOf<Int, TextFile>()
    private val stagedDeletes = mutableMapOf<Int, TextFile>()
    private var nextDraftId = -1

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
        when (preferences.getInt("sort_mode", 1)) { 2 -> sortAlphabetically = true; 3 -> sortNumerically = true }
        lateinit var adapter: TextFileListAdapter
        adapter = TextFileListAdapter({ textFile ->
            val bundle = Bundle().apply {
                putInt("textFileId", textFile.id)
            }
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment, bundle)
        }, { stageUpdate(adapter, it) }, { stageDelete(adapter, it) }, { file -> shareMapAndDnc(file) })
        binding.recyclerview.adapter = adapter
        val addButton = requireActivity().findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.add_fab)
        var fabTransitioning = false
        binding.recyclerview.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (adapter.editMode || fabTransitioning || dy == 0) return
                if (dy > 0 && addButton.isExtended) {
                    fabTransitioning = true
                    addButton.shrink(object : com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton.OnChangedCallback() {
                        override fun onShrunken(extendedFab: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton) { fabTransitioning = false }
                    })
                } else if (dy < 0 && !addButton.isExtended) {
                    fabTransitioning = true
                    addButton.extend(object : com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton.OnChangedCallback() {
                        override fun onExtended(extendedFab: com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton) { fabTransitioning = false }
                    })
                }
            }
        })
        binding.recyclerview.alpha = 0f
        binding.recyclerview.animate().alpha(1f).setDuration(180).start()
        configureToolbarMenu(adapter)
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
            if (adapter.editMode) confirmStagedChanges(adapter)
            else android.app.AlertDialog.Builder(requireContext()).setTitle("Enable edit mode?").setMessage("Renaming or deleting DNC cards makes permanent changes to your saved data.").setNegativeButton("Cancel", null).setPositiveButton("Continue") { _, _ ->
                adapter.editMode = true
                binding.editCardsButton.setIconResource(R.drawable.ic_check)
                binding.editCardsButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
                binding.editCardsButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.on_primary))
                addButton.extend()
                configureAddButton(adapter, true)
            }.show()
        }

        configureAddButton(adapter, false)

        binding.filterButton.setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add(0, 1, 0, "Date edited")
                menu.add(0, 2, 1, "Alphabetically")
                menu.add(0, 3, 2, "Numerically")
                setOnMenuItemClickListener { item ->
                    sortAlphabetically = item.itemId == 2
                    sortNumerically = item.itemId == 3
                    preferences.edit().putInt("sort_mode", item.itemId).apply()
                    submitSorted(adapter)
                    binding.recyclerview.post { binding.recyclerview.scrollToPosition(0) }
                    true
                }
                show()
            }
        }

        viewModel.allTextFiles.observe(viewLifecycleOwner) { files ->
            if (!adapter.editMode) currentFiles = files
            submitSorted(adapter)
            binding.emptyView.visibility = if (currentFiles.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun configureAddButton(adapter: TextFileListAdapter, editing: Boolean) {
        val button = requireActivity().findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.add_fab)
        button.text = if (editing) "Add DNC Card" else "Add DNC"
        button.setOnClickListener {
            if (editing) {
                val title = EditText(requireContext()).apply { hint = "DNC title" }; val number = EditText(requireContext()).apply { hint = "Map No." }
                val form = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(number); addView(title) }
                android.app.AlertDialog.Builder(requireContext()).setTitle("Add DNC Card").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
                    if (title.text.isNotBlank()) {
                        currentFiles = currentFiles + TextFile(id = nextDraftId--, title = title.text.toString(), mapNo = number.text.toString())
                        submitSorted(adapter)
                        binding.emptyView.visibility = View.GONE
                    }
                }.show()
            } else {
                if (currentFiles.isEmpty()) return@setOnClickListener
                quickAdd()
            }
        }
    }

    private fun quickAdd() {
        val labels = currentFiles.sortedWith(compareBy<TextFile> { it.mapNo.substringBefore(" ").toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.mapNo }).map { "${it.mapNo} - ${it.title}" }
        val picker = android.widget.AutoCompleteTextView(requireContext()).apply { hint = "Search DNC card"; setAdapter(android.widget.ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)); threshold = 1; setOnFocusChangeListener { _, focused -> if (focused) showDropDown() }; addTextChangedListener(object : android.text.TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit; override fun afterTextChanged(s: android.text.Editable?) { if (s.isNullOrEmpty()) showDropDown() } }) }
        val now = java.util.Calendar.getInstance(); val date = EditText(requireContext()).apply { hint = "Date (MM/YY)"; setText("%02d/%02d".format(now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.YEAR) % 100)); inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE; setOnClickListener { MonthYearPicker.show(requireContext()) { setText(it) } } }; val address = EditText(requireContext()).apply { hint = "Address" }; val info = EditText(requireContext()).apply { hint = "Supporting information" }
        val form = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(picker); addView(date); addView(address); addView(info) }
        android.app.AlertDialog.Builder(requireContext()).setTitle("Add DNC entry").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ -> val value = date.text.toString(); val selected = currentFiles.firstOrNull { "${it.mapNo} - ${it.title}" == picker.text.toString() }; if (selected == null) android.widget.Toast.makeText(requireContext(), "DNC was not added: select a card", android.widget.Toast.LENGTH_SHORT).show() else if (value.isBlank()) android.widget.Toast.makeText(requireContext(), "DNC was not added: enter a date", android.widget.Toast.LENGTH_SHORT).show() else if (!value.matches(Regex("(0[1-9]|1[0-2])/\\d{2}"))) android.widget.Toast.makeText(requireContext(), "DNC was not added: date must be MM/YY", android.widget.Toast.LENGTH_SHORT).show() else { val entries = com.example.mapasssist.data.DncEntryCodec.decode(selected.entriesJson).toMutableList(); entries += com.example.mapasssist.data.DncEntry(value, address.text.toString(), info.text.toString()); viewModel.update(selected.copy(entriesJson = com.example.mapasssist.data.DncEntryCodec.encode(entries), lastModified = System.currentTimeMillis())); android.widget.Toast.makeText(requireContext(), "DNC added", android.widget.Toast.LENGTH_SHORT).show() } }.show()
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
        adapter.submitList(sorted.filter { file ->
            searchQuery.isBlank() || "${file.mapNo} ${file.title}".contains(searchQuery, ignoreCase = true)
        })
    }

    private fun stageUpdate(adapter: TextFileListAdapter, file: TextFile) {
        if (file.id > 0) stagedUpdates[file.id] = file
        currentFiles = currentFiles.map { if (it.id == file.id) file else it }
        submitSorted(adapter)
        binding.emptyView.visibility = if (currentFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun stageDelete(adapter: TextFileListAdapter, file: TextFile) {
        if (file.id > 0) {
            stagedDeletes[file.id] = file
            stagedUpdates.remove(file.id)
        }
        currentFiles = currentFiles.filterNot { it.id == file.id }
        submitSorted(adapter)
        binding.emptyView.visibility = if (currentFiles.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun confirmStagedChanges(adapter: TextFileListAdapter) {
        val hasChanges = stagedUpdates.isNotEmpty() || stagedDeletes.isNotEmpty() || currentFiles.any { it.id < 0 }
        if (!hasChanges) { leaveEditMode(adapter); return }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Apply DNC card changes?")
            .setMessage("Your changes will be saved permanently. Export a local backup first if you are unsure.")
            .setNegativeButton("Keep editing", null)
            .setPositiveButton("Confirm changes") { _, _ ->
                stagedDeletes.values.forEach(viewModel::delete)
                stagedUpdates.filterKeys { it !in stagedDeletes }.values.forEach(viewModel::update)
                currentFiles.filter { it.id < 0 }.forEach { viewModel.insert(it.copy(id = 0)) }
                stagedUpdates.clear(); stagedDeletes.clear()
                leaveEditMode(adapter)
            }
            .show()
    }

    private fun leaveEditMode(adapter: TextFileListAdapter) {
        adapter.editMode = false
        binding.editCardsButton.setIconResource(R.drawable.ic_edit)
        binding.editCardsButton.backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        binding.editCardsButton.iconTint = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.primary))
        requireActivity().findViewById<com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton>(R.id.add_fab).extend()
        configureAddButton(adapter, false)
    }

    private fun shareMapAndDnc(file: TextFile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val mapUri = withContext(Dispatchers.IO) { MapFileLocator.find(requireContext(), file.mapNo) }
            if (mapUri == null) {
                android.widget.Toast.makeText(requireContext(), "No map image found for map ${file.mapNo}", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                DncImageExporter.shareWithMap(requireContext(), file, mapUri)
            }
        }
    }

    private fun configureToolbarMenu(adapter: TextFileListAdapter) {
        val toolbar = requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        toolbar.post {
            if (_binding == null) return@post
            toolbar.menu.clear()
            val searchView = SearchView(requireContext()).apply {
                queryHint = "Search DNC cards"
                setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                    override fun onQueryTextSubmit(query: String) = true
                    override fun onQueryTextChange(query: String): Boolean { searchQuery = query; submitSorted(adapter); return true }
                })
            }
            toolbar.menu.add("Search").apply {
                setIcon(R.drawable.ic_search)
                actionView = searchView
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS or android.view.MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
                setOnActionExpandListener(object : android.view.MenuItem.OnActionExpandListener {
                    override fun onMenuItemActionExpand(item: android.view.MenuItem): Boolean { toolbar.title = ""; return true }
                    override fun onMenuItemActionCollapse(item: android.view.MenuItem): Boolean { searchQuery = ""; submitSorted(adapter); toolbar.title = getString(R.string.app_name); return true }
                })
            }
            toolbar.menu.add("Export all…").apply {
                setIcon(R.drawable.ic_more)
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
            }
            toolbar.setOnMenuItemClickListener {
                if (it.title == "Export all…") {
                    android.app.AlertDialog.Builder(requireContext())
                        .setTitle("Export all DNC cards?")
                        .setMessage("This will save a PNG image for every DNC card in Downloads/Map Assist. Existing exports with the same name will be replaced.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Export All") { _, _ -> DncImageExporter.downloadAll(requireContext(), currentFiles) }
                        .show()
                    true
                } else false
            }
        }
    }

    override fun onDestroyView() {
        requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).menu.clear()
        super.onDestroyView()
        _binding = null
    }
}
