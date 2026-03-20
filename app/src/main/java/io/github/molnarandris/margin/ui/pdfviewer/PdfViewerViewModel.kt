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
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    val thickness: StrokeThickness = StrokeThickness.MEDIUM
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

    private var nextStrokeId = 0
    private val _completedInkStrokes = MutableStateFlow<Map<Int, List<InkStroke>>>(emptyMap())
    val completedInkStrokes: StateFlow<Map<Int, List<InkStroke>>> = _completedInkStrokes.asStateFlow()

    private val _penColor = MutableStateFlow(StrokeColor.BLACK)
    val penColor: StateFlow<StrokeColor> = _penColor.asStateFlow()

    private val _penThickness = MutableStateFlow(StrokeThickness.MEDIUM)
    val penThickness: StateFlow<StrokeThickness> = _penThickness.asStateFlow()

    fun setPenColor(color: StrokeColor) { _penColor.value = color }
    fun setPenThickness(t: StrokeThickness) { _penThickness.value = t }

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

    fun addInkAnnotation(
        pageIndex: Int, points: List<Offset>, displayWidth: Int, displayHeight: Int,
        color: StrokeColor = _penColor.value,
        thickness: StrokeThickness = _penThickness.value
    ) {
        val normalized = points.map { Offset(it.x / displayWidth, it.y / displayHeight) }
        val strokeId = nextStrokeId++
        _completedInkStrokes.update { map ->
            map + (pageIndex to (map[pageIndex].orEmpty() + InkStroke(strokeId, normalized, color, thickness)))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(pageIndex)
                val pageW = pdPage.mediaBox.width
                val pageH = pdPage.mediaBox.height

                // Build flat float array: [x0, y0, x1, y1, ...]
                val coords = FloatArray(normalized.size * 2) { i ->
                    val pt = normalized[i / 2]
                    if (i % 2 == 0) pt.x * pageW else pageH - pt.y * pageH
                }
                val minX = coords.filterIndexed { i, _ -> i % 2 == 0 }.min()
                val minY = coords.filterIndexed { i, _ -> i % 2 != 0 }.min()
                val maxX = coords.filterIndexed { i, _ -> i % 2 == 0 }.max()
                val maxY = coords.filterIndexed { i, _ -> i % 2 != 0 }.max()

                val innerList = COSArray().apply { coords.forEach { add(COSFloat(it)) } }
                val outerList = COSArray().apply { add(innerList) }
                val rectArr = COSArray().apply {
                    listOf(minX, minY, maxX, maxY).forEach { add(COSFloat(it)) }
                }
                val colorArr = COSArray().apply {
                    color.pdfRgb.forEach { add(COSFloat(it)) }
                }
                val bsDict = COSDictionary().apply {
                    setName(COSName.TYPE, "Border")
                    setName(COSName.SUBTYPE, "S")
                    setItem(COSName.getPDFName("W"), COSFloat(thickness.multiplier * 1.5f))
                }
                val annDict = COSDictionary().apply {
                    setName(COSName.TYPE, "Annot")
                    setName(COSName.SUBTYPE, "Ink")
                    setItem(COSName.INKLIST, outerList)
                    setItem(COSName.RECT, rectArr)
                    setItem(COSName.getPDFName("C"), colorArr)
                    setItem(COSName.getPDFName("BS"), bsDict)
                    setString(COSName.NM, "ink-$strokeId")
                }
                pdPage.annotations.add(PDAnnotationUnknown(annDict))

                app.contentResolver.openOutputStream(uri, "wt")!!.use { pdDoc.save(it) }
                pdDoc.close()

                // Re-open renderer without re-rendering the bitmap
                pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                renderer = PdfRenderer(pfd!!)
            }
        }
    }

    fun eraseInkStrokes(pageIndex: Int, strokeIds: List<Int>) {
        // Remove from overlay immediately
        _completedInkStrokes.update { map ->
            val remaining = map[pageIndex].orEmpty().filter { it.id !in strokeIds }
            if (remaining.isEmpty()) map - pageIndex else map + (pageIndex to remaining)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(pageIndex)
                val namesToRemove = strokeIds.map { "ink-$it" }.toSet()
                val annotations = pdPage.annotations
                val toRemove = annotations.filter { it.annotationName in namesToRemove }
                annotations.removeAll(toRemove)

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

            // Re-extract words and highlights only — ink stroke memory is authoritative
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

    private fun extractInkStrokes(pdDoc: PDDocument, pageIndex: Int, pageW: Float, pageH: Float): List<InkStroke> {
        return try {
            val pdPage = pdDoc.getPage(pageIndex)
            val result = mutableListOf<InkStroke>()
            for (ann in pdPage.annotations) {
                if (ann.getCOSObject().getNameAsString(COSName.SUBTYPE) != "Ink") continue
                val name = ann.annotationName ?: continue
                val id = name.removePrefix("ink-").toIntOrNull() ?: continue
                val outerArr = ann.getCOSObject().getDictionaryObject(COSName.INKLIST) as? COSArray ?: continue
                for (i in 0 until outerArr.size()) {
                    val innerArr = outerArr.getObject(i) as? COSArray ?: continue
                    val points = mutableListOf<Offset>()
                    var j = 0
                    while (j + 1 < innerArr.size()) {
                        val x = (innerArr.getObject(j) as? COSNumber)?.floatValue() ?: break
                        val y = (innerArr.getObject(j + 1) as? COSNumber)?.floatValue() ?: break
                        points.add(Offset(x / pageW, 1f - y / pageH))
                        j += 2
                    }
                    val cosObj = ann.getCOSObject()

                    val cArr = cosObj.getDictionaryObject(COSName.getPDFName("C")) as? COSArray
                    val strokeColor = if (cArr != null && cArr.size() >= 3) {
                        val r = (cArr.getObject(0) as? COSNumber)?.floatValue() ?: 0f
                        val g = (cArr.getObject(1) as? COSNumber)?.floatValue() ?: 0f
                        val b = (cArr.getObject(2) as? COSNumber)?.floatValue() ?: 0f
                        StrokeColor.entries.minByOrNull { c ->
                            val dr = c.pdfRgb[0]-r; val dg = c.pdfRgb[1]-g; val db = c.pdfRgb[2]-b
                            dr*dr + dg*dg + db*db
                        } ?: StrokeColor.BLACK
                    } else StrokeColor.BLACK

                    val bsDict2 = cosObj.getDictionaryObject(COSName.getPDFName("BS")) as? COSDictionary
                    val w = (bsDict2?.getDictionaryObject(COSName.getPDFName("W")) as? COSNumber)?.floatValue() ?: 1.5f
                    val strokeThickness = StrokeThickness.entries.minByOrNull { kotlin.math.abs(it.multiplier * 1.5f - w) }
                        ?: StrokeThickness.MEDIUM

                    if (points.isNotEmpty()) result.add(InkStroke(id, points, strokeColor, strokeThickness))
                }
            }
            result
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

                // Extract ink strokes from saved annotations and populate overlay
                val allInkStrokes = (0 until pageCount).associate { i ->
                    i to extractInkStrokes(pdDoc, i, pages[i].nativeWidth.toFloat(), pages[i].nativeHeight.toFloat())
                }.filter { it.value.isNotEmpty() }
                withContext(Dispatchers.Main) {
                    _completedInkStrokes.value = allInkStrokes
                    val maxId = allInkStrokes.values.flatten().maxOfOrNull { it.id } ?: -1
                    if (maxId >= nextStrokeId) nextStrokeId = maxId + 1
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
