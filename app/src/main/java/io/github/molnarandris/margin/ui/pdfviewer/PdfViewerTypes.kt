package io.github.molnarandris.margin.ui.pdfviewer

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.io.File

sealed class LinkTarget {
    data class Url(val uri: Uri) : LinkTarget()
    data class Goto(val pageNumber: Int, val x: Float, val y: Float, val zoom: Float) : LinkTarget()
}

data class PdfLink(val bounds: List<RectF>, val target: LinkTarget)

data class PdfHighlight(
    val pageIndex: Int,
    val bounds: List<RectF>,    // PR space; one rect per line
    val annotationIndex: Int,   // index in PDPage.annotations list
    val note: String? = null    // PDF Contents field; null = no annotation
)

data class SearchMatch(
    val pageIndex: Int,
    val wordBounds: List<RectF>   // PR space, one rect per matching word
)

data class SearchState(
    val matches: List<SearchMatch> = emptyList(),
    val currentIndex: Int = -1    // -1 = no results
)

data class OutlineItem(val title: String, val pageIndex: Int, val level: Int, val hasChildren: Boolean = false)

enum class StrokeColor(val composeColor: Color, val pdfRgb: FloatArray) {
    BLACK(Color.Black,               floatArrayOf(0f, 0f, 0f)),
    RED  (Color(0xFFE53935.toInt()), floatArrayOf(0.898f, 0.224f, 0.208f)),
    GREEN(Color(0xFF43A047.toInt()), floatArrayOf(0.263f, 0.627f, 0.278f)),
    BLUE (Color(0xFF1E88E5.toInt()), floatArrayOf(0.118f, 0.533f, 0.898f))
}

enum class StrokeThickness(val multiplier: Float) {
    THIN(0.5f), MEDIUM(1.0f), THICK(2.5f)
}

data class InkStroke(
    val id: Int,
    val points: List<Offset>,
    val color: StrokeColor = StrokeColor.BLACK,
    val thickness: StrokeThickness = StrokeThickness.MEDIUM,
    val roundCap: Boolean = false,
    val timestamp: Long = 0L   // ms epoch; 0 = loaded from PDF (already grouped)
)

data class PdfImageAnnotation(
    val id: Int,
    val bitmap: Bitmap,
    val rectNorm: android.graphics.RectF, // left, top, right, bottom in 0–1 page coords (y=0 at top)
    val annotationIndex: Int = -1,        // -1 = not yet persisted to PDF
)

data class PdfPage(
    val bitmap: Bitmap,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val links: List<PdfLink>,
    val words: List<TextWord>,
    val highlights: List<PdfHighlight>,
    val noteLinks: List<Pair<RectF, Int>> = emptyList(),  // bounds (PR space) → note page index
    val isNotePage: Boolean = false
)

data class DocumentMeta(
    val title: String = "",
    val authors: List<String> = emptyList(),
    val projects: List<String> = emptyList(),
    val people: List<String> = emptyList(),
    val arxivId: String = "",
    val createdAt: Long = 0L,
    val isNote: Boolean = false
)

sealed class PdfViewerUiState {
    object Loading : PdfViewerUiState()
    data class Ready(val pages: List<PdfPage>) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
    data class CorruptedWithBackup(val backupFile: File, val uri: Uri) : PdfViewerUiState()
}
