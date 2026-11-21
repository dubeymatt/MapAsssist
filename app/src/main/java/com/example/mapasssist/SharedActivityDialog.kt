package com.example.mapasssist

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.os.Bundle
import androidx.navigation.findNavController
import com.example.mapasssist.data.AppDatabase
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SharedActivityDialog {
    fun show(context: Context) {
        var dialog: AlertDialog? = null
        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val entries = SharedActivityStore.read(context)
        if (entries.isEmpty()) {
            list.addView(TextView(context).apply { text = "No shared changes have been recorded yet."; setPadding(dp(context, 24), dp(context, 18), dp(context, 24), dp(context, 18)) })
        } else entries.forEach { entry ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 20), dp(context, 9), dp(context, 20), dp(context, 9))
                isClickable = true
                isFocusable = true
                val kind = kindOf(entry)
                addView(activityIcon(context, kind))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(context, 12), 0, 0, 0)
                    addView(TextView(context).apply {
                        text = entry.summary
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    })
                    addView(TextView(context).apply {
                        text = "${entry.person} · ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(entry.time))}"
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { dialog?.dismiss(); openTarget(context, entry) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Shared activity")
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton("Close", null)
        builder.setNeutralButton("Clear") { _, _ ->
            MaterialAlertDialogBuilder(context).setTitle("Clear activity on this device?")
                .setMessage("This only clears your activity view. Other members keep their own history.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    SharedActivityStore.clear(context)
                }.show()
        }
        dialog = builder.create()
        dialog.show()
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun activityIcon(context: Context, kind: String): ImageView {
        val accent = if (kind == SharedActivityStore.KIND_LEDGER) {
            androidx.core.content.ContextCompat.getColor(context, R.color.primary)
        } else AccentPreferences.control(context)
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(context, 40), dp(context, 40))
            setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 10))
            setImageResource(if (kind == SharedActivityStore.KIND_LEDGER) R.drawable.ic_ledger else R.drawable.ic_document)
            imageTintList = ColorStateList.valueOf(accent)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 12).toFloat()
                setColor(AccentPreferences.primaryContainer(context))
            }
            contentDescription = if (kind == SharedActivityStore.KIND_LEDGER) "Ledger change" else "DNC change"
        }
    }

    private fun kindOf(entry: SharedActivity): String = when (entry.kind) {
        SharedActivityStore.KIND_DNC, SharedActivityStore.KIND_LEDGER -> entry.kind
        else -> if (entry.summary.contains("ledger", ignoreCase = true) ||
            entry.summary.startsWith("Assigned ") || entry.summary.startsWith("Completed ")) {
            SharedActivityStore.KIND_LEDGER
        } else SharedActivityStore.KIND_DNC
    }

    private fun openTarget(context: Context, entry: SharedActivity) {
        val activity = context as? MainActivity ?: return
        val nav = activity.findNavController(R.id.nav_host_fragment_content_main)
        if (kindOf(entry) == SharedActivityStore.KIND_LEDGER) {
            activity.showLedgerTab(entry.mapNo.ifBlank { legacyMapNo(entry.summary) })
            return
        }
        val mapNo = entry.mapNo.ifBlank { legacyMapNo(entry.summary) }
        if (mapNo.isBlank()) {
            activity.showDncsTab()
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val card = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(context).textFileDao().getAllForBackup().firstOrNull { it.mapNo.equals(mapNo, ignoreCase = true) }
            }
            if (card == null) activity.showDncsTab()
            else runCatching { nav.navigate(R.id.SecondFragment, Bundle().apply { putInt("textFileId", card.id); putString("textFileMapNo", card.mapNo); putString("textFileTitle", card.title) }) }
        }
    }

    private fun legacyMapNo(summary: String): String {
        return Regex("(?:DNC card|Ledger entry)\\s+([A-Za-z]*\\d+[A-Za-z]*)", RegexOption.IGNORE_CASE)
            .find(summary)?.groupValues?.getOrNull(1).orEmpty()
    }
}
