package com.example.mapasssist

import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.databinding.DncEntryItemBinding
import java.util.Calendar

class DncEntryAdapter(private val entries: MutableList<DncEntry>, private val startDrag: (RecyclerView.ViewHolder) -> Unit) : RecyclerView.Adapter<DncEntryAdapter.EntryHolder>() {
    var editMode = false
        set(value) { field = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(DncEntryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = entries.size
    override fun onBindViewHolder(holder: EntryHolder, position: Int) = holder.bind(position)
    fun addEntry() { entries += DncEntry(); notifyItemInserted(entries.lastIndex) }
    fun values() = entries.toList()
    fun move(from: Int, to: Int) { if (from in entries.indices && to in entries.indices) { entries.add(to, entries.removeAt(from)); notifyItemMoved(from, to) } }

    inner class EntryHolder(private val binding: DncEntryItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int) {
            val item = entries[position]
            binding.entryDate.setText(item.date)
            binding.entryAddress.setText(item.address)
            binding.entrySupportingInformation.setText(item.supportingInformation)
            binding.dragHandle.visibility = if (editMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.dragHandle.setOnTouchListener { _, event -> if (editMode && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) startDrag(this); false }
            binding.deleteEntry.visibility = if (editMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.deleteEntry.setOnClickListener { val index = adapterPosition; if (index != RecyclerView.NO_POSITION) { entries.removeAt(index); notifyItemRemoved(index) } }
            binding.entryDate.setOnClickListener {
                val calendar = Calendar.getInstance()
                DatePickerDialog(binding.root.context, { _, year, month, _ ->
                    binding.entryDate.setText("%02d/%02d".format(month + 1, year % 100))
                }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
            }
            binding.entryDate.doAfterTextChanged { update(adapterPosition, date = it.toString()) }
            binding.entryAddress.doAfterTextChanged { update(adapterPosition, address = it.toString()) }
            binding.entrySupportingInformation.doAfterTextChanged { update(adapterPosition, supporting = it.toString()) }
        }
    }

    private fun update(position: Int, date: String? = null, address: String? = null, supporting: String? = null) {
        if (position !in entries.indices) return
        val old = entries[position]
        entries[position] = old.copy(date = date ?: old.date, address = address ?: old.address, supportingInformation = supporting ?: old.supportingInformation)
    }
}
