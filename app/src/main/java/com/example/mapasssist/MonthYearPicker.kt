package com.example.mapasssist

import android.app.AlertDialog
import android.content.Context
import android.widget.LinearLayout
import android.widget.NumberPicker

object MonthYearPicker {
    fun show(context: Context, onSelected: (String) -> Unit) {
        val now = java.util.Calendar.getInstance()
        val month = NumberPicker(context).apply { minValue = 1; maxValue = 12; value = now.get(java.util.Calendar.MONTH) + 1; wrapSelectorWheel = false; displayedValues = Array(12) { "%02d".format(it + 1) } }
        val year = NumberPicker(context).apply { minValue = 1970; maxValue = now.get(java.util.Calendar.YEAR); value = now.get(java.util.Calendar.YEAR); wrapSelectorWheel = false }
        val content = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(48, 24, 48, 8); addView(month, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); addView(year, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        AlertDialog.Builder(context).setTitle("Select month and year").setView(content).setNegativeButton("Cancel", null).setPositiveButton("Done") { _, _ -> onSelected("%02d/%02d".format(month.value, year.value % 100)) }.show()
    }
}
