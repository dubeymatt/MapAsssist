package com.example.mapasssist

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.OnBackPressedCallback
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile
import com.example.mapasssist.data.TextFileViewModel
import com.example.mapasssist.databinding.FragmentSecondBinding
import kotlinx.coroutines.launch

class SecondFragment : Fragment() {
    private var _binding: FragmentSecondBinding? = null
    private val binding get() = _binding!!
    private val viewModel: TextFileViewModel by viewModels()
    private var currentTextFile: TextFile? = null
    private val entries = mutableListOf<DncEntry>()
    private var entryAdapter: DncEntryAdapter? = null
    private var saved = false
    private var originalEntries = ""
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View { _binding = FragmentSecondBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lateinit var touchHelper: ItemTouchHelper
        lateinit var adapter: DncEntryAdapter
        adapter = DncEntryAdapter(entries, { holder -> touchHelper.startDrag(holder) }, { if (adapter.editMode) saved = false else autoSave() }, { position -> showEditDialog(adapter, position) }) { position -> confirmDelete(adapter, position) }
        entryAdapter = adapter
        binding.entriesRecyclerview.layoutManager = LinearLayoutManager(requireContext())
        binding.entriesRecyclerview.adapter = adapter
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean { if (!adapter.editMode) return false; adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition); return true }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
            override fun isLongPressDragEnabled() = false
        })
        touchHelper.attachToRecyclerView(binding.entriesRecyclerview)
        val id = arguments?.getInt("textFileId") ?: -1
        if (id != -1) lifecycleScope.launch {
            currentTextFile = viewModel.getTextFileById(id)
            currentTextFile?.let { file ->
                binding.editTitle.setText(file.title); binding.editMapNo.setText(file.mapNo)
                binding.editTitle.isEnabled = false; binding.editMapNo.isEnabled = false
                requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).title = "${file.mapNo} - ${file.title}"
                entries += DncEntryCodec.decode(file.entriesJson).ifEmpty { if (file.content.isNotBlank()) listOf(DncEntry(supportingInformation = file.content)) else emptyList() }
                originalEntries = DncEntryCodec.encode(entries)
                adapter.notifyDataSetChanged()
            }
        }
        binding.addEntryButton.text = "Add DNC"
        binding.addEntryButton.setOnClickListener { if (adapter.editMode) setEntryEditMode(adapter, false) else showAddDialog(adapter) }
        binding.editEntriesButton.setOnClickListener { setEntryEditMode(adapter, true) }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.editMode && hasChanges()) showSaveChangesDialog(adapter) { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed() }
                else { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed() }
            }
        })
    }
    private fun showAddDialog(adapter: DncEntryAdapter) {
        val now = java.util.Calendar.getInstance(); val date = android.widget.EditText(requireContext()).apply { hint = "Date (MM/YY)"; setText("%02d/%02d".format(now.get(java.util.Calendar.MONTH) + 1, now.get(java.util.Calendar.YEAR) % 100)); inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE; setOnClickListener { MonthYearPicker.show(requireContext()) { setText(it) } } }
        val address = android.widget.EditText(requireContext()).apply { hint = "Address" }
        val info = android.widget.EditText(requireContext()).apply { hint = "Supporting information" }
        val form = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(date); addView(address); addView(info) }
        android.app.AlertDialog.Builder(requireContext()).setTitle("Add DNC entry").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Add") { _, _ ->
            val value = date.text.toString(); if (value.isBlank()) Toast.makeText(requireContext(), "DNC was not added: enter a date", Toast.LENGTH_SHORT).show() else if (!value.matches(Regex("(0[1-9]|1[0-2])/\\d{2}"))) Toast.makeText(requireContext(), "DNC was not added: date must be MM/YY", Toast.LENGTH_SHORT).show() else { entries += DncEntry(value, address.text.toString(), info.text.toString()); adapter.notifyItemInserted(entries.lastIndex); autoSave(); Toast.makeText(requireContext(), "DNC added", Toast.LENGTH_SHORT).show() }
        }.show()
    }
    private fun setEntryEditMode(adapter: DncEntryAdapter, enabled: Boolean) {
        if (!enabled && hasChanges()) autoSave()
        adapter.editMode = enabled
        binding.editEntriesButton.visibility = if (enabled) View.INVISIBLE else View.VISIBLE
        binding.addEntryButton.apply {
            text = if (enabled) "Confirm" else "Add DNC"
            setIconResource(if (enabled) R.drawable.ic_check else R.drawable.ic_add)
            layoutParams = layoutParams.apply { width = ViewGroup.LayoutParams.WRAP_CONTENT }
            backgroundTintList = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.primary))
            iconTint = android.content.res.ColorStateList.valueOf(androidx.core.content.ContextCompat.getColor(requireContext(), R.color.on_primary))
            requestLayout()
        }
    }
    private fun confirmDelete(adapter: DncEntryAdapter, position: Int) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete DNC entry?")
            .setMessage("This entry will be removed when you confirm your edits.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ -> adapter.deleteEntry(position) }
            .show()
    }
    private fun showSaveChangesDialog(adapter: DncEntryAdapter, onContinue: () -> Unit) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Save changes?")
            .setMessage("You have unconfirmed changes to this DNC card.")
            .setNegativeButton("Discard") { _, _ ->
                entries.clear(); entries += DncEntryCodec.decode(originalEntries); adapter.notifyDataSetChanged(); setEntryEditMode(adapter, false); onContinue()
            }
            .setNeutralButton("Keep editing", null)
            .setPositiveButton("Save changes") { _, _ -> setEntryEditMode(adapter, false); onContinue() }
            .show()
    }
    private fun showEditDialog(adapter: DncEntryAdapter, position: Int) {
        val entry = adapter.values().getOrNull(position) ?: return
        val date = android.widget.EditText(requireContext()).apply { hint = "Date (MM/YY)"; setText(entry.date); inputType = android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE; setOnClickListener { MonthYearPicker.show(requireContext()) { setText(it) } } }
        val address = android.widget.EditText(requireContext()).apply { hint = "Address"; setText(entry.address) }
        val info = android.widget.EditText(requireContext()).apply { hint = "Supporting information"; setText(entry.supportingInformation) }
        val form = android.widget.LinearLayout(requireContext()).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(48, 0, 48, 0); addView(date); addView(address); addView(info) }
        android.app.AlertDialog.Builder(requireContext()).setTitle("Edit DNC entry").setView(form).setNegativeButton("Cancel", null).setPositiveButton("Update") { _, _ ->
            val value = date.text.toString()
            if (value.isBlank()) Toast.makeText(requireContext(), "DNC was not updated: enter a date", Toast.LENGTH_SHORT).show()
            else if (!value.matches(Regex("(0[1-9]|1[0-2])/\\d{2}"))) Toast.makeText(requireContext(), "DNC was not updated: date must be MM/YY", Toast.LENGTH_SHORT).show()
            else { adapter.updateEntry(position, DncEntry(value, address.text.toString(), info.text.toString())); Toast.makeText(requireContext(), "DNC updated", Toast.LENGTH_SHORT).show() }
        }.show()
    }
    private fun save(adapter: DncEntryAdapter) {
        val title = binding.editTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) { Toast.makeText(requireContext(), "Enter a DNC title", Toast.LENGTH_SHORT).show(); return }
        val rows = adapter.values().filter { it.date.isNotBlank() || it.address.isNotBlank() || it.supportingInformation.isNotBlank() }
        if (rows.any { it.date.isNotBlank() && !it.date.matches(Regex("(0[1-9]|1[0-2])/\\d{2}")) }) { Toast.makeText(requireContext(), "Use MM/YY for dates", Toast.LENGTH_SHORT).show(); return }
        val file = currentTextFile?.copy(title = title, mapNo = binding.editMapNo.text?.toString()?.trim().orEmpty(), entriesJson = DncEntryCodec.encode(rows), lastModified = System.currentTimeMillis()) ?: TextFile(title = title, mapNo = binding.editMapNo.text?.toString()?.trim().orEmpty(), entriesJson = DncEntryCodec.encode(rows))
        if (file.id == 0) viewModel.insert(file) else viewModel.update(file)
        saved = true
        findNavController().navigateUp()
    }
    private fun autoSave() { val file = currentTextFile ?: return; originalEntries = DncEntryCodec.encode(entries); viewModel.update(file.copy(entriesJson = originalEntries, lastModified = System.currentTimeMillis())); saved = true }
    fun requestNavigateUp(): Boolean {
        val adapter = entryAdapter ?: return false
        if (!adapter.editMode || !hasChanges()) return false
        showSaveChangesDialog(adapter) { findNavController().navigateUp() }
        return true
    }
    private fun hasChanges(): Boolean = DncEntryCodec.encode(entries) != originalEntries
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
