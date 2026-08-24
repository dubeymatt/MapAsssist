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
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View { _binding = FragmentSecondBinding.inflate(inflater, container, false); return binding.root }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lateinit var touchHelper: ItemTouchHelper
        val adapter = DncEntryAdapter(entries) { holder -> touchHelper.startDrag(holder) }
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
                adapter.notifyDataSetChanged()
            }
        }
        binding.addEntryButton.setOnClickListener { adapter.addEntry() }
        binding.editEntriesButton.setOnClickListener { adapter.editMode = !adapter.editMode }
        binding.buttonShare.setOnClickListener { save(adapter) }
    }
    private fun save(adapter: DncEntryAdapter) {
        val title = binding.editTitle.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) { Toast.makeText(requireContext(), "Enter a DNC title", Toast.LENGTH_SHORT).show(); return }
        val rows = adapter.values().filter { it.date.isNotBlank() || it.address.isNotBlank() || it.supportingInformation.isNotBlank() }
        if (rows.any { it.date.isNotBlank() && !it.date.matches(Regex("(0[1-9]|1[0-2])/\\d{2}")) }) { Toast.makeText(requireContext(), "Use MM/YY for dates", Toast.LENGTH_SHORT).show(); return }
        val file = currentTextFile?.copy(title = title, mapNo = binding.editMapNo.text?.toString()?.trim().orEmpty(), entriesJson = DncEntryCodec.encode(rows), lastModified = System.currentTimeMillis()) ?: TextFile(title = title, mapNo = binding.editMapNo.text?.toString()?.trim().orEmpty(), entriesJson = DncEntryCodec.encode(rows))
        if (file.id == 0) viewModel.insert(file) else viewModel.update(file)
        findNavController().navigateUp()
    }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
