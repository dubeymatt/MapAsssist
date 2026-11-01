package com.example.mapasssist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.pdf.PrintedPdfDocument
import com.example.mapasssist.data.DncEntry
import com.example.mapasssist.data.DncEntryCodec
import com.example.mapasssist.data.TextFile
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/** Compact, centred A5 layout for Android's built-in print and Save as PDF flow. */
class MapDncPrintDocumentAdapter(
    private val context: Context,
    private val card: TextFile,
    private val mapUri: android.net.Uri
) : PrintDocumentAdapter() {
    private var attributes: PrintAttributes? = null

    fun print() {
        (context.getSystemService(Context.PRINT_SERVICE) as android.print.PrintManager).print(
            "Map Assist - ${card.mapNo} ${card.title}", this,
            PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4.asLandscape())
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .setColorMode(PrintAttributes.COLOR_MODE_COLOR).build()
        )
    }

    override fun onLayout(old: PrintAttributes?, next: PrintAttributes, signal: CancellationSignal, callback: LayoutResultCallback, extras: android.os.Bundle?) {
        attributes = next
        callback.onLayoutFinished(
            PrintDocumentInfo.Builder("map_assist_${card.mapNo}.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), old != next
        )
    }

    override fun onWrite(ranges: Array<PageRange>, destination: ParcelFileDescriptor, signal: CancellationSignal, callback: WriteResultCallback) {
        val attrs = attributes ?: run { callback.onWriteFailed("Print settings unavailable"); return }
        runCatching {
            val document = PrintedPdfDocument(context, attrs)
            try {
                val map = context.contentResolver.openInputStream(mapUri)?.use(BitmapFactory::decodeStream)
                render(document, map, DncEntryCodec.decode(card.entriesJson), signal)
                FileOutputStream(destination.fileDescriptor).use(document::writeTo)
            } finally { document.close() }
        }.onSuccess { callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
            .onFailure { callback.onWriteFailed(it.message) }
    }

    private fun render(document: PrintedPdfDocument, map: Bitmap?, entries: List<DncEntry>, signal: CancellationSignal) {
        var index = 0
        var pageNumber = 0
        do {
            if (signal.isCanceled) return
            pageNumber++
            val page = document.startPage(pageNumber)
            val canvas = page.canvas
            val width = page.info.pageWidth.toFloat()
            val height = page.info.pageHeight.toFloat()
            val gutter = 10f
            val panelWidth = (width - gutter * 3f) / 2f
            val firstPage = pageNumber == 1
            // The same card is rendered into both A5-sized halves of A4 so
            // the sheet can be cut down the middle after printing.
            val nextIndex = drawCard(canvas, gutter, panelWidth, height, map, entries, index, firstPage)
            drawCard(canvas, gutter * 2f + panelWidth, panelWidth, height, map, entries, index, firstPage)
            index = nextIndex
            document.finishPage(page)
        } while (index < entries.size || (entries.isEmpty() && pageNumber == 1))
    }

    private fun drawCard(
        canvas: Canvas,
        offsetX: Float,
        width: Float,
        height: Float,
        map: Bitmap?,
        entries: List<DncEntry>,
        startIndex: Int,
        firstPage: Boolean
    ): Int {
            canvas.save()
            canvas.translate(offsetX, 0f)
            val margin = width * 0.05f
            val left = margin
            val right = width - margin
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(29, 38, 33) }
            var y = margin

            paint.textSize = width * 0.034f
            paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("${card.mapNo} - ${card.title}", width / 2f, y - paint.fontMetrics.ascent, paint)
            y += paint.fontMetrics.descent - paint.fontMetrics.ascent + width * 0.025f

            if (firstPage && map != null) {
                // Both dimensions are scaled by one factor, so the map never distorts.
                val availableWidth = right - left
                // Reserve half the A5 page for the map. The image is centred
                // in that area and scaled proportionally, never stretched.
                val mapAreaHeight = height * 0.45f
                val scale = min(availableWidth / map.width, mapAreaHeight / map.height)
                val mapWidth = map.width * scale
                val mapHeight = map.height * scale
                val mapTop = y + (mapAreaHeight - mapHeight) / 2f
                val box = RectF((width - mapWidth) / 2f, mapTop, (width + mapWidth) / 2f, mapTop + mapHeight)
                canvas.drawBitmap(map, null, box, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.7f
                paint.color = BORDER_COLOR
                canvas.drawRect(box, paint)
                paint.style = Paint.Style.FILL
                paint.color = Color.rgb(29, 38, 33)
                y += mapAreaHeight + 6f
            }

            val dateRight = left + (right - left) * 0.15f
            val addressRight = left + (right - left) * 0.60f
            val headerHeight = 18f
            drawHeader(canvas, paint, left, dateRight, addressRight, right, y, headerHeight)
            y += headerHeight
            // Compact print size leaves room for twenty single-line entries
            // while the map retains its fixed half-page area.
            paint.textSize = 8f
            paint.typeface = android.graphics.Typeface.DEFAULT
            val lineHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent + 1f
            val bottom = height - margin

            var index = startIndex
            while (index < entries.size) {
                val entry = entries[index]
                val address = wrap(paint, entry.address, addressRight - dateRight - width * 0.016f)
                val info = wrap(paint, entry.supportingInformation, right - addressRight - width * 0.016f)
                val lines = max(1, max(address.size, info.size))
                val rowHeight = max(11f, lines * lineHeight + 2f)
                if (y + rowHeight > bottom && index > 0) break
                drawRow(canvas, paint, entry.date, address, info, left, dateRight, addressRight, right, y, rowHeight, lineHeight)
                y += rowHeight
                index++
            }
            canvas.restore()
            return index
    }

    private fun drawHeader(canvas: Canvas, paint: Paint, left: Float, dateRight: Float, addressRight: Float, right: Float, top: Float, height: Float) {
        paint.color = Color.rgb(224, 224, 224)
        canvas.drawRect(left, top, right, top + height, paint)
        paint.color = BORDER_COLOR
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.7f
        canvas.drawRect(left, top, right, top + height, paint)
        canvas.drawLine(dateRight, top, dateRight, top + height, paint); canvas.drawLine(addressRight, top, addressRight, top + height, paint)
        paint.style = Paint.Style.FILL
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        paint.isFakeBoldText = true
        paint.textSize = 7f
        drawCenteredHeaderText(canvas, paint, "Date", left, dateRight, top, height)
        drawCenteredHeaderText(canvas, paint, "Address", dateRight, addressRight, top, height)
        drawCenteredHeaderText(canvas, paint, "Supporting information", addressRight, right, top, height)
        paint.isFakeBoldText = false
    }

    private fun drawRow(canvas: Canvas, paint: Paint, date: String, address: List<String>, info: List<String>, left: Float, dateRight: Float, addressRight: Float, right: Float, top: Float, height: Float, lineHeight: Float) {
        paint.color = Color.WHITE; canvas.drawRect(left, top, right, top + height, paint)
        paint.color = BORDER_COLOR; paint.style = Paint.Style.STROKE; paint.strokeWidth = 0.7f
        canvas.drawRect(left, top, right, top + height, paint)
        canvas.drawLine(dateRight, top, dateRight, top + height, paint); canvas.drawLine(addressRight, top, addressRight, top + height, paint)
        paint.style = Paint.Style.FILL; paint.color = Color.rgb(29, 38, 33)
        paint.textAlign = Paint.Align.CENTER; drawLines(canvas, paint, listOf(date), (left + dateRight) / 2f, top, height, lineHeight)
        paint.textAlign = Paint.Align.LEFT; drawLines(canvas, paint, address, dateRight + 7f, top, height, lineHeight); drawLines(canvas, paint, info, addressRight + 7f, top, height, lineHeight)
    }

    private fun center(canvas: Canvas, paint: Paint, text: String, left: Float, right: Float, top: Float, height: Float) {
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, (left + right) / 2f, top + (height - paint.fontMetrics.descent + paint.fontMetrics.ascent) / 2f, paint)
    }

    private fun drawCenteredHeaderText(canvas: Canvas, paint: Paint, text: String, left: Float, right: Float, top: Float, height: Float) {
        paint.textAlign = Paint.Align.CENTER
        val baseline = top + height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        canvas.drawText(text, (left + right) / 2f, baseline, paint)
    }

    private fun drawLines(canvas: Canvas, paint: Paint, lines: List<String>, x: Float, top: Float, height: Float, lineHeight: Float) {
        val actual = lines.ifEmpty { listOf("") }
        val block = paint.fontMetrics.descent - paint.fontMetrics.ascent + (actual.size - 1) * lineHeight
        var baseline = top + (height - block) / 2f - paint.fontMetrics.ascent
        actual.forEach { canvas.drawText(it, x, baseline, paint); baseline += lineHeight }
    }

    private fun wrap(paint: Paint, source: String, width: Float): List<String> {
        val words = source.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>(); var line = ""
        words.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width) line = candidate else { if (line.isNotEmpty()) lines += line; line = word }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    private companion object {
        val BORDER_COLOR: Int = Color.rgb(110, 110, 110)
    }
}
