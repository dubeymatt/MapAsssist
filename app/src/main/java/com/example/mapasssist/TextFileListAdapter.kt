package com.example.mapasssist

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.mapasssist.data.TextFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TextFileListAdapter(private val open: (TextFile) -> Unit, private val updated: (TextFile) -> Unit, private val deleted: (TextFile) -> Unit) : ListAdapter<TextFile, TextFileListAdapter.Holder>(Diff()) {
    private var grid = false
    var editMode = false
        set(value) { field = value; notifyDataSetChanged() }
    fun setGridMode(enabled: Boolean) { if (grid != enabled) { grid = enabled; notifyDataSetChanged() } }
    override fun getItemViewType(position: Int) = if (grid) 1 else 0
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LayoutInflater.from(parent.context).inflate(if (viewType == 1) R.layout.recyclerview_grid_item else R.layout.recyclerview_item, parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title = view.findViewById<TextView>(R.id.textViewTitle); private val date = view.findViewById<TextView>(R.id.textViewDate); private val badge = view.findViewById<TextView>(R.id.map_number_badge); private val delete = view.findViewById<View?>(R.id.delete_card); private val download = view.findViewById<View?>(R.id.download_card); private val share = view.findViewById<View?>(R.id.share_card); private val actions = view.findViewById<View?>(R.id.card_actions)
        fun bind(file: TextFile) {
            title.text = file.title; badge.text = file.mapNo; date.text = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()).format(Date(file.lastModified)); delete?.visibility = if (editMode) View.VISIBLE else View.GONE; actions?.visibility = if (editMode) View.GONE else View.VISIBLE; download?.visibility = if (editMode) View.GONE else View.VISIBLE; share?.visibility = if (editMode) View.GONE else View.VISIBLE
            title.isClickable = editMode; badge.isClickable = editMode; itemView.isClickable = true
            title.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (editMode) R.drawable.ic_edit else 0, 0); badge.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
            title.setOnClickListener(if (editMode) View.OnClickListener { android.app.AlertDialog.Builder(itemView.context).setTitle("Edit DNC Card").setItems(arrayOf("Rename DNC", "Change Map No.")) { _, which -> edit(file, which == 1) }.show() } else null); badge.setOnClickListener(null); delete?.setOnClickListener { android.app.AlertDialog.Builder(itemView.context).setTitle("Permanently delete DNC card?").setMessage("This cannot be undone. All addresses and supporting information in ${file.title} will be permanently deleted.").setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> deleted(file) }.show() }; itemView.setOnClickListener { if (!editMode) open(file) }
            download?.setOnClickListener { DncImageExporter.download(itemView.context, file) }; share?.setOnClickListener { DncImageExporter.share(itemView.context, file) }
        }
        private fun edit(file: TextFile, number: Boolean) {
            val input = android.widget.EditText(itemView.context).apply { setText(if (number) file.mapNo else file.title); selectAll() }
            android.app.AlertDialog.Builder(itemView.context).setTitle(if (number) "Edit Map No." else "Rename DNC").setView(input).setNegativeButton("Cancel", null).setPositiveButton("Save") { _, _ -> updated(if (number) file.copy(mapNo = input.text.toString(), lastModified = System.currentTimeMillis()) else file.copy(title = input.text.toString(), lastModified = System.currentTimeMillis())) }.show()
        }
    }
    class Diff : DiffUtil.ItemCallback<TextFile>() { override fun areItemsTheSame(a: TextFile, b: TextFile) = a.id == b.id; override fun areContentsTheSame(a: TextFile, b: TextFile) = a == b }
}
