package com.example.mapasssist

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SharedActivityDialog {
    fun show(context: Context) {
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val entries = SharedActivityStore.read(context)
        if (entries.isEmpty()) {
            list.addView(TextView(context).apply { text = "No shared changes have been recorded yet."; setPadding(dp(context, 24), dp(context, 18), dp(context, 24), dp(context, 18)) })
        } else entries.forEach { entry ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10))
                addView(TextView(context).apply { text = entry.summary; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                addView(TextView(context).apply {
                    text = "${entry.person} · ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(entry.time))}"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Shared activity")
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton("Close", null)
            .show()
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
}
