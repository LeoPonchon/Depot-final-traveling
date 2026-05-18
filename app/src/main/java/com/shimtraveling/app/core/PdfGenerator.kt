package com.shimtraveling.core

import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfDocument
import android.os.Environment
import com.shimtraveling.data.model.TravelPath
import com.shimtraveling.features.common.PdfViewerActivity
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PdfGenerator(private val context: Context) {

    fun generatePathPdf(path: TravelPath): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        var page = document.startPage(pageInfo)
        var canvas = page.canvas

        val titlePaint = android.graphics.Paint().apply {
            textSize = 24f
            isFakeBoldText = true
            color = android.graphics.Color.parseColor("#1A237E")
        }

        val subtitlePaint = android.graphics.Paint().apply {
            textSize = 14f
            isFakeBoldText = true
            color = android.graphics.Color.parseColor("#3F51B5")
        }

        val textPaint = android.graphics.Paint().apply {
            textSize = 12f
            color = android.graphics.Color.BLACK
        }

        val slotPaint = android.graphics.Paint().apply {
            textSize = 11f
            isFakeBoldText = true
            color = android.graphics.Color.parseColor("#757575")
        }

        val pathCostText = if (path.hasCompletePricing && path.totalCost != null) {
            String.format("%.2f €", path.totalCost)
        } else {
            "Prix indisponible"
        }

        canvas.drawText("Mon Parcours Voyage: ${path.name}", 50f, 50f, titlePaint)
        canvas.drawText("Date: ${SimpleDateFormat("dd/MM/yyyy").format(Date())}", 50f, 80f, textPaint)
        canvas.drawText("Durée totale: ${path.formattedDuration} | Coût: $pathCostText", 50f, 105f, textPaint)
        canvas.drawText("Distance: ${String.format("%.1f km", path.distanceKm)} | Effort: ${path.totalEffort.getDisplayName()}", 50f, 125f, textPaint)

        var yPosition = 160f
        canvas.drawLine(50f, yPosition, 545f, yPosition, textPaint)
        yPosition += 30f

        canvas.drawText("Détails des étapes:", 50f, yPosition, subtitlePaint)
        yPosition += 30f

        path.steps.sortedBy { it.order }.forEach { step ->
            val slot = when (step.timeOfDay) {
                com.shimtraveling.data.model.TimeOfDay.MORNING -> "MATIN"
                com.shimtraveling.data.model.TimeOfDay.AFTERNOON -> "APRÈS-MIDI"
                com.shimtraveling.data.model.TimeOfDay.EVENING -> "SOIR"
                null -> "INCONNU"
            }

            canvas.drawText("[$slot]", 50f, yPosition, slotPaint)
            yPosition += 18f
            canvas.drawText("${step.order}. ${step.placeName}", 50f, yPosition, textPaint)
            yPosition += 15f
            canvas.drawText("   Type: ${step.activityType.getDisplayName()} | Durée: ${step.estimatedDurationMinutes} min", 60f, yPosition, textPaint)
            yPosition += 15f
            val stepCostText = step.estimatedCost?.let { String.format("%.2f €", it) } ?: "Prix indisponible"
            canvas.drawText("   Coût estimé: $stepCostText", 60f, yPosition, textPaint)
            yPosition += 25f

            if (yPosition > 780f) {
                document.finishPage(page)
                page = document.startPage(pageInfo)
                canvas = page.canvas
                yPosition = 50f
            }
        }

        document.finishPage(page)

        val fileName = "parcours_${path.name.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        try {
            document.writeTo(FileOutputStream(file))
        } finally {
            document.close()
        }

        return file
    }

    fun openPdf(file: File) {
        val intent = PdfViewerActivity.createIntent(context, file, file.name).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}
