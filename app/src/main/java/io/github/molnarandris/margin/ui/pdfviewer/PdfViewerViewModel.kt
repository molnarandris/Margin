package io.github.molnarandris.margin.ui.pdfviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
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
import kotlinx.coroutines.async
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

data class TextChar(val text: String, val bounds: RectF)

data class TextWord(
    val text: String,
    val bounds: RectF,   // PR space (top-left origin)
    val chars: List<TextChar>
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
    data class Ready(val pages: List<PdfPage>, val title: String = "", val author: String = "") : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
}

private class WordExtractor(private val pageCount: Int) : PDFTextStripper() {
    val wordsByPage: Array<MutableList<TextWord>> = Array(pageCount) { mutableListOf() }
    private val currentWordChars = mutableListOf<TextPosition>()

    override fun writeString(text: String, positions: List<TextPosition>) {
        for (pos in positions) {
            if (pos.unicode.isBlank()) flushWord() else currentWordChars.add(pos)
        }
        flushWord()
    }

    private fun flushWord() {
        if (currentWordChars.isEmpty()) return
        val pageIdx = currentPageNo - 1  // PDFTextStripper is 1-based
        if (pageIdx !in wordsByPage.indices) { currentWordChars.clear(); return }
        val text   = currentWordChars.joinToString("") { it.unicode }
        val left   = currentWordChars.minOf { it.x }
        val right  = currentWordChars.maxOf { it.x + it.width }
        val top    = currentWordChars.minOf { it.y }
        val bottom = currentWordChars.maxOf { it.y + it.height }
        val chars  = currentWordChars.map { pos ->
            TextChar(pos.unicode, RectF(pos.x, pos.y, pos.x + pos.width, pos.y + pos.height))
        }
        wordsByPage[pageIdx].add(TextWord(text, RectF(left, top, right, bottom), chars))
        currentWordChars.clear()
    }
}

private class SinglePageWordExtractor : PDFTextStripper() {
    val words = mutableListOf<TextWord>()
    private val currentWordChars = mutableListOf<TextPosition>()

    override fun writeString(text: String, positions: List<TextPosition>) {
        for (pos in positions) {
            if (pos.unicode.isBlank()) flushWord() else currentWordChars.add(pos)
        }
        flushWord()
    }

    private fun flushWord() {
        if (currentWordChars.isEmpty()) return
        val text   = currentWordChars.joinToString("") { it.unicode }
        val left   = currentWordChars.minOf { it.x }
        val right  = currentWordChars.maxOf { it.x + it.width }
        val top    = currentWordChars.minOf { it.y }
        val bottom = currentWordChars.maxOf { it.y + it.height }
        val chars  = currentWordChars.map { pos ->
            TextChar(pos.unicode, RectF(pos.x, pos.y, pos.x + pos.width, pos.y + pos.height))
        }
        words.add(TextWord(text, RectF(left, top, right, bottom), chars))
        currentWordChars.clear()
    }
}

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Loading)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _displayTitle = MutableStateFlow("")
    val displayTitle: StateFlow<String> = _displayTitle.asStateFlow()

    private val _displayAuthor = MutableStateFlow("")
    val displayAuthor: StateFlow<String> = _displayAuthor.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderMutex = Mutex()
    private var rerenderJob: Job? = null
    private var currentRenderScale = 2f
    private var docUri: Uri? = null
    var firstVisiblePageIndex: Int = 0

    fun onVisiblePageChanged(index: Int) {
        firstVisiblePageIndex = index
    }

    fun loadPdf(dirUri: Uri, docId: String) {
        rerenderJob?.cancel()
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            val app = getApplication<Application>()
            val uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
            val fileName = withContext(Dispatchers.IO) {
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0).removeSuffix(".pdf") else ""
                    } ?: ""
            }
            renderPages(dirUri, docId, fileName)
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
            _uiState.value = state.copy(pages = newPages)
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

    fun addHighlight(pageIndex: Int, selectedChars: List<TextChar>) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(pageIndex)
                val pageH = pdPage.mediaBox.height

                val sortedChars = selectedChars.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                val lines = groupCharsIntoLines(sortedChars)
                val quads = FloatArray(lines.size * 8)
                lines.forEachIndexed { i, lineChars ->
                    val left        = lineChars.minOf { it.bounds.left }
                    val right       = lineChars.maxOf { it.bounds.right }
                    // bounds.top = visual bottom (baseline); bounds.top - height() = visual top
                    // mirrors the canvas rendering formula exactly
                    val prVisualTop = lineChars.minOf { it.bounds.top - it.bounds.height() }
                    val prVisualBot = lineChars.maxOf { it.bounds.top }
                    val pbTop = pageH - prVisualTop   // PDF Y-up: visual top → larger value
                    val pbBot = pageH - prVisualBot   // PDF Y-up: visual bottom → smaller value
                    val base = i * 8
                    quads[base+0] = left;  quads[base+1] = pbTop
                    quads[base+2] = right; quads[base+3] = pbTop
                    quads[base+4] = left;  quads[base+5] = pbBot
                    quads[base+6] = right; quads[base+7] = pbBot
                }
                val allLeft      = selectedChars.minOf { it.bounds.left }
                val allRight     = selectedChars.maxOf { it.bounds.right }
                val allVisualTop = selectedChars.minOf { it.bounds.top - it.bounds.height() }
                val allVisualBot = selectedChars.maxOf { it.bounds.top }
                val ann = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                    quadPoints = quads
                    color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                    rectangle = PDRectangle(allLeft, pageH - allVisualBot, allRight - allLeft, allVisualBot - allVisualTop)
                }
                pdPage.annotations.add(ann)

                app.contentResolver.openOutputStream(uri, "wt")!!.use { pdDoc.save(it) }
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
                app.contentResolver.openOutputStream(uri, "wt")!!.use { pdDoc.save(it) }
                pdDoc.close()

                reloadPage(uri, app, highlight.pageIndex)
            }
        }
    }

    private fun groupCharsIntoLines(chars: List<TextChar>): List<List<TextChar>> {
        if (chars.isEmpty()) return emptyList()
        val threshold = chars.map { it.bounds.height() }.average().toFloat() * 0.5f
        val lines = mutableListOf<MutableList<TextChar>>()
        for (char in chars) {
            val line = lines.firstOrNull { kotlin.math.abs(it.first().bounds.top - char.bounds.top) < threshold }
            if (line != null) line.add(char) else lines.add(mutableListOf(char))
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
            _uiState.value = state.copy(pages = newPages)
        }
    }

    private fun extractAllWords(pdDoc: PDDocument, pageCount: Int): List<List<TextWord>> {
        return try {
            val extractor = WordExtractor(pageCount)
            extractor.getText(pdDoc)
            extractor.wordsByPage.map { it.toList() }
        } catch (e: Exception) {
            List(pageCount) { emptyList() }
        }
    }

    private fun extractWords(pdDoc: PDDocument, pageIndex: Int): List<TextWord> {
        return try {
            val extractor = SinglePageWordExtractor()
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

    private suspend fun renderPages(dirUri: Uri, docId: String, fileName: String): Unit =
        withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                PDFBoxResourceLoader.init(app)

                val uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                this@PdfViewerViewModel.docUri = uri

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r")
                    ?: run {
                        withContext(Dispatchers.Main) {
                            _uiState.value = PdfViewerUiState.Error("Could not open file")
                        }
                        return@withContext
                    }

                pfd?.close()
                renderer?.close()
                pfd = newPfd
                val newRenderer = PdfRenderer(newPfd)
                renderer = newRenderer
                currentRenderScale = 2f

                // Start PDFBox load while PdfRenderer was being set up
                val pdDocDeferred = async { PDDocument.load(app.contentResolver.openInputStream(uri)!!) }

                // Extract metadata first so TopAppBar shows title/author before bitmaps arrive
                val pdDoc = pdDocDeferred.await()
                val title  = pdDoc.documentInformation?.title?.takeIf  { it.isNotBlank() } ?: ""
                val author = pdDoc.documentInformation?.author?.takeIf { it.isNotBlank() } ?: ""
                withContext(Dispatchers.Main) {
                    _displayTitle.value = title.ifBlank { fileName }
                    _displayAuthor.value = author
                }

                // --- PHASE 1: bitmaps + links only ---
                val pages = renderMutex.withLock {
                    (0 until newRenderer.pageCount).map { index ->
                        newRenderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                (page.width * 2f).toInt(),
                                (page.height * 2f).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            val links = mutableListOf<PdfLink>()
                            page.getLinkContents().forEach { links.add(PdfLink(it.bounds, LinkTarget.Url(it.uri))) }
                            page.getGotoLinks().forEach { dest ->
                                links.add(PdfLink(dest.bounds, LinkTarget.Goto(dest.destination.pageNumber,
                                    dest.destination.xCoordinate, dest.destination.yCoordinate, dest.destination.zoom)))
                            }
                            PdfPage(bitmap, page.width, page.height, links, emptyList(), emptyList())
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Ready(pages, title, author)
                }

                val pageCount = pages.size

                // Extract all highlights first — fast, not coupled to word extraction
                val allHighlights = (0 until pageCount).map { i ->
                    extractHighlights(pdDoc, i, pages[i].nativeHeight.toFloat())
                }
                val pagesWithHighlights = pages.mapIndexed { i, page ->
                    page.copy(highlights = allHighlights[i])
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Ready(pagesWithHighlights, title, author)
                }

                // Extract words (slow) and emit final state
                val allWords = extractAllWords(pdDoc, pageCount)
                pdDoc.close()

                val enrichedPages = pagesWithHighlights.mapIndexed { i, page ->
                    page.copy(words = allWords[i])
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Ready(enrichedPages, title, author)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Error(e.message ?: "Failed to render PDF")
                }
            }
        }

    fun setMetadata(newTitle: String, newAuthor: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val info = pdDoc.documentInformation
                info.title  = newTitle
                info.author = newAuthor
                pdDoc.documentInformation = info  // re-attach in case no /Info dict existed
                app.contentResolver.openOutputStream(uri, "wt")!!.use { pdDoc.save(it) }
                pdDoc.close()

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)
            }
            val state = _uiState.value as? PdfViewerUiState.Ready ?: return@launch
            withContext(Dispatchers.Main) {
                _displayTitle.value = newTitle
                _displayAuthor.value = newAuthor
                _uiState.value = state.copy(title = newTitle, author = newAuthor)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        renderer?.close()
        pfd?.close()
    }
}
