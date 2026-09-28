package com.example.mapasssist

import android.app.AlertDialog
import android.content.ContentValues
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object DncImageExporter {
    private const val IMAGE_WIDTH = 1200
    private const val TABLE_LEFT = 21f
    private const val TABLE_RIGHT = 1179f
    private const val DATE_X = 37f
    private const val DATE_CENTER_X = 81f
    private const val ADDRESS_X = 160f
    private const val SUPPORTING_X = 753f
    private const val DATE_DIVIDER_X = 141f
    private const val SUPPORTING_DIVIDER_X = 733f
    private const val TABLE_TOP = 253f
    private const val CELL_FONT_SIZE = 39f
    private const val CELL_LINE_HEIGHT = 47f

    fun share(context: Context, file: TextFile) {
        val uri = cacheImage(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share DNC card"))
    }

    fun shareWithMap(context: Context, file: TextFile, mapUri: Uri) {
        val dncUri = cacheImage(context, file)
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(mapUri, dncUri))
            clipData = ClipData.newUri(context.contentResolver, "Map", mapUri).apply { addItem(ClipData.Item(dncUri)) }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share map and DNC card"))
    }

    fun download(context: Context, file: TextFile) {
        val name = safeName(file) + ".png"
        val alreadyExists = exportedFilesExist(context, name)

        if (alreadyExists) {
            AlertDialog.Builder(context)
                .setTitle("Replace existing image?")
                .setMessage("$name already exists in Downloads/Map Assist. Replacing it cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Replace") { _, _ -> saveDownload(context, file, name, true) }
                .show()
        } else {
            saveDownload(context, file, name, false)
        }
    }

    fun downloadAll(context: Context, files: List<TextFile>) {
        files.forEach { file ->
            val name = safeName(file) + ".png"
            deletePreviousExports(context, name)
            saveDownload(context, file, name, replace = false, showMessage = false)
        }
        android.widget.Toast.makeText(context, "Saved ${files.size} DNC cards to Downloads/Map Assist", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun saveDownload(context: Context, file: TextFile, name: String, replace: Boolean, showMessage: Boolean = true) {
        if (replace) {
            deletePreviousExports(context, name)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Map Assist/")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            render(file).compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        if (showMessage) android.widget.Toast.makeText(context, "Saved to Downloads/Map Assist", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Removes the exact export plus Android's old '(1)', '(2)' conflict copies. */
    private fun deletePreviousExports(context: Context, name: String) {
        val stem = name.substringBeforeLast('.')
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND (${MediaStore.MediaColumns.DISPLAY_NAME}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)",
            arrayOf("Download/Map Assist/", name, "$stem (%).png")
        )
    }

    private fun exportedFilesExist(context: Context, name: String): Boolean {
        val stem = name.substringBeforeLast('.')
        return context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND (${MediaStore.MediaColumns.DISPLAY_NAME}=? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?)",
            arrayOf("Download/Map Assist/", name, "$stem (%).png"),
            null
        )?.use { it.moveToFirst() } == true
    }

    private fun cacheImage(context: Context, file: TextFile): Uri {
        val directory = File(context.cacheDir, "images").apply { mkdirs() }
        val image = File.createTempFile("dnc_${file.id}_", ".png", directory)
        FileOutputStream(image).use { stream -> render(file).compress(Bitmap.CompressFormat.PNG, 100, stream) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", image)
    }

    private fun render(file: TextFile): Bitmap {
        val rows = DncEntryCodec.decode(file.entriesJson)
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = CELL_FONT_SIZE }
        val renderedRows = rows.map { entry -> createRenderedRow(entry, bodyPaint) }
        val tableHeight = renderedRows.sumOf { it.height.toInt() }
        val imageHeight = max(400, TABLE_TOP.toInt() + tableHeight + 133)
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, imageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.rgb(252, 249, 242))
        paint.color = Color.rgb(23, 33, 29)
        paint.textAlign = Paint.Align.LEFT
        paint.textSize = 45f
        canvas.drawText("DNC Record", 64f, 77f, paint)
        paint.textSize = 61f
        canvas.drawText("${file.mapNo} - ${file.title}", 64f, 149f, paint)
        paint.textSize = 33f
        canvas.drawText("Date", DATE_X, 216f, paint)
        canvas.drawText("Address", ADDRESS_X, 216f, paint)
        canvas.drawText("Supporting information", SUPPORTING_X, 216f, paint)

        var y = TABLE_TOP
        renderedRows.forEachIndexed { index, row ->
            paint.color = if (index % 2 == 0) Color.rgb(242, 237, 226) else Color.rgb(252, 249, 242)
            canvas.drawRect(TABLE_LEFT, y, TABLE_RIGHT, y + row.height, paint)
            paint.color = Color.rgb(23, 33, 29)
            paint.textSize = CELL_FONT_SIZE
            paint.textAlign = Paint.Align.CENTER
            drawLines(canvas, paint, listOf(pngDate(row.entry.date)), DATE_CENTER_X, y, row.height)
            paint.textAlign = Paint.Align.LEFT
            drawLines(canvas, paint, row.addressLines, ADDRESS_X, y, row.height)
            drawLines(canvas, paint, row.supportingLines, SUPPORTING_X, y, row.height)
            y += row.height
        }

        paint.color = Color.rgb(205, 199, 188)
        paint.strokeWidth = 2f
        canvas.drawLine(DATE_DIVIDER_X, 184f, DATE_DIVIDER_X, y, paint)
        canvas.drawLine(SUPPORTING_DIVIDER_X, 184f, SUPPORTING_DIVIDER_X, y, paint)

        paint.color = Color.rgb(89, 97, 93)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 29f
        canvas.drawText("Created on ${SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())}", IMAGE_WIDTH / 2f, imageHeight - 99f, paint)
        canvas.drawText("Please draw new entries to the attention of the Territory Servant.", IMAGE_WIDTH / 2f, imageHeight - 62f, paint)
        return bitmap
    }

    private fun createRenderedRow(entry: DncEntry, paint: Paint): RenderedRow {
        val address = wrapLines(paint, entry.address, SUPPORTING_DIVIDER_X - ADDRESS_X - 10f)
        val supporting = wrapLines(paint, entry.supportingInformation, TABLE_RIGHT - SUPPORTING_X - 10f)
        val lineCount = max(1, max(address.size, supporting.size))
        val fontHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
        val contentHeight = fontHeight + (lineCount - 1) * CELL_LINE_HEIGHT
        val height = max(75f, contentHeight + 24f).toInt()
        return RenderedRow(entry, address, supporting, height.toFloat())
    }

    private fun drawLines(canvas: Canvas, paint: Paint, lines: List<String>, x: Float, rowTop: Float, rowHeight: Float) {
        if (lines.isEmpty()) return
        val metrics = paint.fontMetrics
        val blockHeight = (metrics.descent - metrics.ascent) + (lines.size - 1) * CELL_LINE_HEIGHT
        val firstBaseline = rowTop + (rowHeight - blockHeight) / 2f - metrics.ascent
        lines.forEachIndexed { index, line -> canvas.drawText(line, x, firstBaseline + index * CELL_LINE_HEIGHT, paint) }
    }

    private fun wrapLines(paint: Paint, value: String, width: Float): List<String> {
        val words = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        words.forEach { word ->
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(candidate) <= width) {
                current = candidate
            } else {
                if (current.isNotEmpty()) lines += current
                current = ""
                var remaining = word
                while (paint.measureText(remaining) > width) {
                    var splitAt = 1
                    while (splitAt < remaining.length && paint.measureText(remaining.substring(0, splitAt + 1)) <= width) splitAt++
                    lines += remaining.substring(0, splitAt)
                    remaining = remaining.substring(splitAt)
                }
                current = remaining
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun safeName(file: TextFile) = ("${file.mapNo}_${file.title}").replace(Regex("[^A-Za-z0-9_-]"), "_")

    private fun pngDate(value: String): String {
        val match = Regex("(0?[1-9]|1[0-2])[/.-](\\d{2,4})").find(value) ?: return value
        return "%02d/%02d".format(match.groupValues[1].toInt(), match.groupValues[2].takeLast(2).toInt())
    }

    private data class RenderedRow(
        val entry: DncEntry,
        val addressLines: List<String>,
        val supportingLines: List<String>,
        val height: Float
    )
}
