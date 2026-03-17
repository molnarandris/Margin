package io.github.molnarandris.margin.ui.pdfviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class LinkTarget {
    data class Url(val uri: Uri) : LinkTarget()
    data class Goto(val pageNumber: Int, val x: Float, val y: Float, val zoom: Float) : LinkTarget()
}

data class PdfLink(val bounds: List<RectF>, val target: LinkTarget)

data class TextWord(
    val text: String,
    val bounds: RectF   // PR space (top-left origin)
)

data class PdfHighlight(
    val pageIndex: Int,
    val bounds: List<RectF>,    // PR space; one rect per line
    val annotationIndex: Int    // index in PDPage.annotations list
)

data class SearchMatch(
    val pageIndex: Int,
    val wordBounds: List<RectF>   // PR space, one rect per matching word
)

data class SearchState(
    val matches: List<SearchMatch> = emptyList(),
    val currentIndex: Int = -1    // -1 = no results
)

data class PdfPage(
    val bitmap: Bitmap,
    val nativeWidth: Int,
    val nativeHeight: Int,
    val links: List<PdfLink>,
    val words: List<TextWord>,
    val highlights: List<PdfHighlight>
)

sealed class PdfViewerUiState {
    object Loading : PdfViewerUiState()
    data class Ready(val pages: List<PdfPage>) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
}

private class WordExtractor : PDFTextStripper() {
    val words = mutableListOf<TextWord>()
    private val currentWordChars = mutableListOf<TextPosition>()

    override fun writeString(text: String, positions: List<TextPosition>) {
        for (pos in positions) {
            if (pos.unicode.isBlank()) {
                flushWord()
            } else {
                currentWordChars.add(pos)
            }
        }
        flushWord()
    }

    private fun flushWord() {
        if (currentWordChars.isEmpty()) return
        val text = currentWordChars.joinToString("") { it.unicode }
        val left  = currentWordChars.minOf { it.x }
        val right = currentWordChars.maxOf { it.x + it.width }
        // TextPosition.getY() is already in screen space (Y down from top of page).
        val top    = currentWordChars.minOf { it.y }
        val bottom = currentWordChars.maxOf { it.y + it.height }
        words.add(TextWord(text, RectF(left, top, right, bottom)))
        currentWordChars.clear()
    }
}

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Loading)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderMutex = Mutex()
    private var rerenderJob: Job? = null
    private var currentRenderScale = 2f
    private var docUri: Uri? = null

    fun loadPdf(dirUri: Uri, docId: String) {
        rerenderJob?.cancel()
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            _uiState.value = renderPages(dirUri, docId)
        }
    }

    fun updateRenderScale(displayScale: Float, visibleIndices: List<Int>) {
        val targetScale = (displayScale * 2f).coerceIn(2f, 8f)
        if (kotlin.math.abs(targetScale - currentRenderScale) < 0.5f) return
        rerenderJob?.cancel()
        rerenderJob = viewModelScope.launch {
            val state = _uiState.value as? PdfViewerUiState.Ready ?: return@launch
            val newPages = state.pages.toMutableList()
            renderMutex.withLock {
                withContext(Dispatchers.IO) {
                    val r = renderer ?: return@withContext
                    currentRenderScale = targetScale
                    for (index in visibleIndices) {
                        if (index !in newPages.indices) continue
                        r.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                (page.width * targetScale).toInt(),
                                (page.height * targetScale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            newPages[index] = newPages[index].copy(bitmap = bitmap)
                        }
                    }
                }
            }
            _uiState.value = PdfViewerUiState.Ready(newPages)
        }
    }

    fun search(query: String) {
        viewModelScope.launch(Dispatchers.Default) {
            if (query.isBlank()) {
                _searchState.value = SearchState()
                return@launch
            }
            val pages = (_uiState.value as? PdfViewerUiState.Ready)?.pages ?: return@launch
            val allMatches = mutableListOf<SearchMatch>()
            val lowerQuery = query.lowercase()
            for ((pageIndex, page) in pages.withIndex()) {
                val words = page.words
                if (words.isEmpty()) continue
                // Build concatenated text with word char ranges
                val wordRanges = mutableListOf<IntRange>()
                val sb = StringBuilder()
                for (word in words) {
                    val start = sb.length
                    sb.append(word.text)
                    wordRanges.add(start until sb.length)
                    sb.append(' ')
                }
                val text = sb.toString().lowercase()
                var idx = 0
                while (true) {
                    val found = text.indexOf(lowerQuery, idx)
                    if (found < 0) break
                    val end = found + lowerQuery.length
                    val matchBounds = words.indices
                        .filter { i -> wordRanges[i].first < end && wordRanges[i].last >= found }
                        .map { i -> words[i].bounds }
                    if (matchBounds.isNotEmpty()) {
                        allMatches.add(SearchMatch(pageIndex, matchBounds))
                    }
                    idx = found + 1
                }
            }
            _searchState.value = SearchState(allMatches, if (allMatches.isEmpty()) -1 else 0)
        }
    }

    fun nextMatch() {
        val s = _searchState.value
        if (s.matches.isEmpty()) return
        _searchState.value = s.copy(currentIndex = (s.currentIndex + 1) % s.matches.size)
    }

    fun prevMatch() {
        val s = _searchState.value
        if (s.matches.isEmpty()) return
        _searchState.value = s.copy(currentIndex = (s.currentIndex - 1 + s.matches.size) % s.matches.size)
    }

    fun clearSearch() {
        _searchState.value = SearchState()
    }

    fun addHighlight(pageIndex: Int, selectedWords: List<TextWord>) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(pageIndex)
                val pageH = pdPage.mediaBox.height

                val sortedWords = selectedWords.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                val lines = groupIntoLines(sortedWords)
                val quads = FloatArray(lines.size * 8)
                lines.forEachIndexed { i, lineWords ->
                    val left        = lineWords.minOf { it.bounds.left }
                    val right       = lineWords.maxOf { it.bounds.right }
                    // bounds.top = visual bottom (baseline); bounds.top - height() = visual top
                    // mirrors the canvas rendering formula exactly
                    val prVisualTop = lineWords.minOf { it.bounds.top - it.bounds.height() }
                    val prVisualBot = lineWords.maxOf { it.bounds.top }
                    val pbTop = pageH - prVisualTop   // PDF Y-up: visual top → larger value
                    val pbBot = pageH - prVisualBot   // PDF Y-up: visual bottom → smaller value
                    val base = i * 8
                    quads[base+0] = left;  quads[base+1] = pbTop
                    quads[base+2] = right; quads[base+3] = pbTop
                    quads[base+4] = left;  quads[base+5] = pbBot
                    quads[base+6] = right; quads[base+7] = pbBot
                }
                val allLeft      = selectedWords.minOf { it.bounds.left }
                val allRight     = selectedWords.maxOf { it.bounds.right }
                val allVisualTop = selectedWords.minOf { it.bounds.top - it.bounds.height() }
                val allVisualBot = selectedWords.maxOf { it.bounds.top }
                val ann = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                    quadPoints = quads
                    color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                    rectangle = PDRectangle(allLeft, pageH - allVisualBot, allRight - allLeft, allVisualBot - allVisualTop)
                }
                pdPage.annotations.add(ann)

                app.contentResolver.openOutputStream(uri)!!.use { pdDoc.save(it) }
                pdDoc.close()

                reloadPage(uri, app, pageIndex)
            }
        }
    }

    fun deleteHighlight(highlight: PdfHighlight) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(highlight.pageIndex)
                val annotations = pdPage.annotations
                if (highlight.annotationIndex in annotations.indices) {
                    annotations.removeAt(highlight.annotationIndex)
                }
                app.contentResolver.openOutputStream(uri)!!.use { pdDoc.save(it) }
                pdDoc.close()

                reloadPage(uri, app, highlight.pageIndex)
            }
        }
    }

    private fun groupIntoLines(words: List<TextWord>): List<List<TextWord>> {
        if (words.isEmpty()) return emptyList()
        val threshold = words.map { it.bounds.height() }.average().toFloat() * 0.5f
        val lines = mutableListOf<MutableList<TextWord>>()
        for (word in words) {
            val line = lines.firstOrNull { kotlin.math.abs(it.first().bounds.top - word.bounds.top) < threshold }
            if (line != null) line.add(word) else lines.add(mutableListOf(word))
        }
        return lines
    }

    // Must be called while renderMutex is held and renderer/pfd are closed
    private suspend fun reloadPage(uri: Uri, app: Application, pageIndex: Int) {
        val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return
        pfd = newPfd
        val newRenderer = PdfRenderer(newPfd)
        renderer = newRenderer

        val state = _uiState.value as? PdfViewerUiState.Ready ?: return
        val newPages = state.pages.toMutableList()

        // Re-render bitmap
        newRenderer.openPage(pageIndex).use { page ->
            val scale = currentRenderScale
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt(),
                (page.height * scale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // Re-extract words and highlights
            val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
            val words = extractWords(pdDoc, pageIndex)
            val highlights = extractHighlights(pdDoc, pageIndex, page.height.toFloat())
            pdDoc.close()

            newPages[pageIndex] = newPages[pageIndex].copy(
                bitmap = bitmap,
                words = words,
                highlights = highlights
            )
        }

        withContext(Dispatchers.Main) {
            _uiState.value = PdfViewerUiState.Ready(newPages)
        }
    }

    private fun extractWords(pdDoc: PDDocument, pageIndex: Int): List<TextWord> {
        return try {
            val extractor = WordExtractor()
            extractor.startPage = pageIndex + 1
            extractor.endPage   = pageIndex + 1
            extractor.getText(pdDoc)
            extractor.words.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractHighlights(pdDoc: PDDocument, pageIndex: Int, pageHeight: Float): List<PdfHighlight> {
        return try {
            val pdPage = pdDoc.getPage(pageIndex)
            // Keep the real index into pdPage.annotations so deletion removes the right entry
            pdPage.annotations
                .mapIndexed { realIdx, ann -> realIdx to ann }
                .filter { (_, ann) ->
                    ann is PDAnnotationTextMarkup &&
                    (ann as PDAnnotationTextMarkup).subtype == PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                }
                .map { (realIdx, ann) ->
                    ann as PDAnnotationTextMarkup
                    val quads = ann.quadPoints ?: floatArrayOf()
                    val rects = quads.toList().chunked(8).mapNotNull { q ->
                        if (q.size < 8) return@mapNotNull null
                        val minX  = minOf(q[0], q[2], q[4], q[6])
                        val maxX  = maxOf(q[0], q[2], q[4], q[6])
                        val pbTop = maxOf(q[1], q[3], q[5], q[7])
                        val pbBot = minOf(q[1], q[3], q[5], q[7])
                        RectF(minX, pageHeight - pbTop, maxX, pageHeight - pbBot)
                    }
                    PdfHighlight(pageIndex, rects, realIdx)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun renderPages(dirUri: Uri, docId: String): PdfViewerUiState =
        withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                PDFBoxResourceLoader.init(app)

                val uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                this@PdfViewerViewModel.docUri = uri

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r")
                    ?: return@withContext PdfViewerUiState.Error("Could not open file")

                pfd?.close()
                renderer?.close()
                pfd = newPfd
                val newRenderer = PdfRenderer(newPfd)
                renderer = newRenderer
                currentRenderScale = 2f

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)

                renderMutex.withLock {
                    val pages = (0 until newRenderer.pageCount).map { index ->
                        newRenderer.openPage(index).use { page ->
                            val scale = 2f
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                            val links = mutableListOf<PdfLink>()
                            page.getLinkContents().forEach { link ->
                                links.add(PdfLink(link.bounds, LinkTarget.Url(link.uri)))
                            }
                            page.getGotoLinks().forEach { link ->
                                val dest = link.destination
                                links.add(PdfLink(link.bounds, LinkTarget.Goto(dest.pageNumber, dest.xCoordinate, dest.yCoordinate, dest.zoom)))
                            }

                            val words = extractWords(pdDoc, index)
                            val highlights = extractHighlights(pdDoc, index, page.height.toFloat())

                            PdfPage(bitmap, page.width, page.height, links, words, highlights)
                        }
                    }
                    pdDoc.close()
                    PdfViewerUiState.Ready(pages)
                }
            } catch (e: Exception) {
                PdfViewerUiState.Error(e.message ?: "Failed to render PDF")
            }
        }

    override fun onCleared() {
        super.onCleared()
        renderer?.close()
        pfd?.close()
    }
}
