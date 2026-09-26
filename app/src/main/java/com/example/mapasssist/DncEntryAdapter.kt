package com.example.mapasssist

import android.app.DatePickerDialog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.databinding.DncEntryItemBinding
import java.util.Calendar

class DncEntryAdapter(private val entries: MutableList<DncEntry>, private val startDrag: (RecyclerView.ViewHolder) -> Unit, private val onChanged: () -> Unit, private val onEntryClicked: (Int) -> Unit, private val onDeleteRequested: (Int) -> Unit) : RecyclerView.Adapter<DncEntryAdapter.EntryHolder>() {
    var editMode = false
        set(value) { field = value; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = EntryHolder(DncEntryItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun getItemCount() = entries.size
    override fun onBindViewHolder(holder: EntryHolder, position: Int) = holder.bind(position)
    fun addEntry() { entries += DncEntry(); notifyItemInserted(entries.lastIndex); onChanged() }
    fun values() = entries.toList()
    fun move(from: Int, to: Int) { if (from in entries.indices && to in entries.indices) { entries.add(to, entries.removeAt(from)); notifyItemMoved(from, to); onChanged() } }
    fun updateEntry(position: Int, entry: DncEntry) { if (position in entries.indices) { entries[position] = entry; notifyItemChanged(position); onChanged() } }
    fun deleteEntry(position: Int) { if (position in entries.indices) { entries.removeAt(position); notifyItemRemoved(position); onChanged() } }

    inner class EntryHolder(private val binding: DncEntryItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(position: Int) {
            val item = entries[position]
            binding.displayDate.text = item.date; binding.displayAddress.text = item.address; binding.displaySupportingInformation.text = item.supportingInformation
            val dark = (binding.root.context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            val shade = if (position % 2 == 0) if (dark) android.graphics.Color.rgb(31, 43, 37) else android.graphics.Color.rgb(238, 242, 238) else if (dark) android.graphics.Color.rgb(16, 21, 18) else android.graphics.Color.TRANSPARENT
            binding.root.setBackgroundColor(shade)
            binding.displayFields.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val textColor = if (dark) android.graphics.Color.rgb(244, 247, 242) else android.graphics.Color.rgb(23, 33, 29)
            binding.displayDate.setTextColor(textColor); binding.displayAddress.setTextColor(textColor); binding.displaySupportingInformation.setTextColor(textColor)
            binding.displayFields.visibility = android.view.View.VISIBLE
            binding.editorFields.visibility = android.view.View.GONE
            binding.dragHandle.visibility = if (editMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.dragHandle.setOnTouchListener { _, event -> if (editMode && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) startDrag(this); false }
            binding.deleteEntry.visibility = if (editMode) android.view.View.VISIBLE else android.view.View.GONE
            binding.deleteEntry.setOnClickListener { val index = adapterPosition; if (index != RecyclerView.NO_POSITION) onDeleteRequested(index) }
            binding.displayFields.setOnClickListener { val index = adapterPosition; if (editMode && index != RecyclerView.NO_POSITION) onEntryClicked(index) }
        }
    }
}
