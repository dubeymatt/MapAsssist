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
import java.util.*

class TextFileListAdapter(private val onItemClicked: (TextFile) -> Unit) :
    ListAdapter<TextFile, TextFileListAdapter.TextFileViewHolder>(TextFileComparator()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TextFileViewHolder {
        return TextFileViewHolder.create(parent)
    }

    override fun onBindViewHolder(holder: TextFileViewHolder, position: Int) {
        val current = getItem(position)
        holder.bind(current)
        holder.itemView.setOnClickListener {
            onItemClicked(current)
        }
    }

    class TextFileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.textViewTitle)
        private val contentView: TextView = itemView.findViewById(R.id.textViewContent)
        private val dateView: TextView = itemView.findViewById(R.id.textViewDate)

        fun bind(textFile: TextFile) {
            titleView.text = textFile.title
            contentView.text = textFile.content
            val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
            dateView.text = sdf.format(Date(textFile.lastModified))
        }

        companion object {
            fun create(parent: ViewGroup): TextFileViewHolder {
                val view: View = LayoutInflater.from(parent.context)
                    .inflate(R.layout.recyclerview_item, parent, false)
                return TextFileViewHolder(view)
            }
        }
    }

    class TextFileComparator : DiffUtil.ItemCallback<TextFile>() {
        override fun areItemsTheSame(oldItem: TextFile, newItem: TextFile): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: TextFile, newItem: TextFile): Boolean {
            return oldItem.title == newItem.title && oldItem.content == newItem.content
        }
    }
}
