package com.example.mapasssist

import android.content.Context
import android.view.ViewGroup
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
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 24), dp(context, 10), dp(context, 24), dp(context, 10))
                isClickable = true
                foreground = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                addView(TextView(context).apply { text = entry.summary; setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge) })
                addView(TextView(context).apply {
                    text = "${entry.person} · ${SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(entry.time))}"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                })
                setOnClickListener { dialog?.dismiss(); openTarget(context, entry) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle("Shared activity")
            .setView(ScrollView(context).apply { addView(list) })
            .setPositiveButton("Close", null)
        if (FirebaseSharedLibrary.isHost(context)) builder.setNeutralButton("Clear") { _, _ ->
            MaterialAlertDialogBuilder(context).setTitle("Clear shared activity?")
                .setMessage("This removes the activity history for every library member.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    CoroutineScope(Dispatchers.Main).launch { runCatching { FirebaseSharedLibrary.clearActivity(context) } }
                }.show()
        }
        dialog = builder.create()
        dialog.show()
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private fun openTarget(context: Context, entry: SharedActivity) {
        val activity = context as? MainActivity ?: return
        val nav = activity.findNavController(R.id.nav_host_fragment_content_main)
        if (entry.summary.contains("Assigned ") || entry.summary.contains("Ledger")) {
            runCatching { nav.navigate(R.id.LedgerFragment) }
            return
        }
        val match = Regex("(?:Added|Modified) DNC card (.+?) –").find(entry.summary) ?: return
        val mapNo = match.groupValues[1]
        CoroutineScope(Dispatchers.Main).launch {
            val card = withContext(Dispatchers.IO) { AppDatabase.getDatabase(context).textFileDao().getAllForBackup().firstOrNull { it.mapNo == mapNo } } ?: return@launch
            runCatching { nav.navigate(R.id.SecondFragment, Bundle().apply { putInt("textFileId", card.id); putString("textFileMapNo", card.mapNo); putString("textFileTitle", card.title) }) }
        }
    }
}
