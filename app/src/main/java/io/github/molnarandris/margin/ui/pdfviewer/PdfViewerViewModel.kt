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
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.content.Context
import io.github.molnarandris.margin.data.PdfRepository
import io.github.molnarandris.margin.data.PreferencesRepository
import java.io.File

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
    val roundCap: Boolean = false
)

sealed class UndoableAction {
    data class StrokeAdded(val pageIndex: Int, val stroke: InkStroke) : UndoableAction()
    data class StrokesErased(val pageIndex: Int, val strokes: List<InkStroke>) : UndoableAction()
    data class PageJumped(val fromPage: Int, val toPage: Int) : UndoableAction()
    // Highlights identified by bounds (not annotationIndex, which can drift after add/delete)
    data class HighlightAdded(val pageIndex: Int, val bounds: List<RectF>, val note: String?) : UndoableAction()
    data class HighlightDeleted(val pageIndex: Int, val bounds: List<RectF>, val note: String?) : UndoableAction()
    data class AnnotationEdited(val pageIndex: Int, val bounds: List<RectF>, val oldNote: String?, val newNote: String?) : UndoableAction()
    data class MetadataChanged(val oldTitle: String, val newTitle: String, val oldAuthor: String, val newAuthor: String, val oldProjects: List<String>, val newProjects: List<String>) : UndoableAction()
    data class StrokesMoved(val pageIndex: Int, val originalStrokes: List<InkStroke>, val movedStrokes: List<InkStroke>) : UndoableAction()
}

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
    data class Ready(val pages: List<PdfPage>, val title: String = "", val author: String = "", val projects: List<String> = emptyList()) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
    data class CorruptedWithBackup(val backupFile: File, val uri: Uri) : PdfViewerUiState()
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

    private val _displayProjects = MutableStateFlow<List<String>>(emptyList())
    val displayProjects: StateFlow<List<String>> = _displayProjects.asStateFlow()

    private val _searchState = MutableStateFlow(SearchState())
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    private val _outline = MutableStateFlow<List<OutlineItem>>(emptyList())
    val outline: StateFlow<List<OutlineItem>> = _outline.asStateFlow()

    private var nextStrokeId = 0
    private val _completedInkStrokes = MutableStateFlow<Map<Int, List<InkStroke>>>(emptyMap())
    val completedInkStrokes: StateFlow<Map<Int, List<InkStroke>>> = _completedInkStrokes.asStateFlow()

    private val _inkClipboard = MutableStateFlow<List<InkStroke>?>(null)
    val inkClipboard: StateFlow<List<InkStroke>?> = _inkClipboard.asStateFlow()

    private val _penColor = MutableStateFlow(StrokeColor.BLACK)
    val penColor: StateFlow<StrokeColor> = _penColor.asStateFlow()

    private val _penThickness = MutableStateFlow(StrokeThickness.MEDIUM)
    val penThickness: StateFlow<StrokeThickness> = _penThickness.asStateFlow()

    private val prefsRepo = PreferencesRepository(application)
    private val pdfRepository = PdfRepository(application)

    init {
        viewModelScope.launch {
            prefsRepo.getPenColor()
                ?.let { name -> StrokeColor.entries.firstOrNull { it.name == name } }
                ?.let { _penColor.value = it }
            prefsRepo.getPenThickness()
                ?.let { name -> StrokeThickness.entries.firstOrNull { it.name == name } }
                ?.let { _penThickness.value = it }
        }
    }

    fun setPenColor(color: StrokeColor) {
        _penColor.value = color
        viewModelScope.launch { prefsRepo.savePenColor(color.name) }
    }

    fun setPenThickness(t: StrokeThickness) {
        _penThickness.value = t
        viewModelScope.launch { prefsRepo.savePenThickness(t.name) }
    }

    private val undoStack = ArrayDeque<UndoableAction>()  // max 10
    private val redoStack = ArrayDeque<UndoableAction>()
    private var isUndoRedoInProgress = false
    private val _canUndo = MutableStateFlow(false)
    private val _canRedo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun pushUndo(action: UndoableAction) {
        if (isUndoRedoInProgress) return
        if (undoStack.size >= 10) undoStack.removeFirst()
        undoStack.addLast(action)
        redoStack.clear()
        _canUndo.value = true
        _canRedo.value = false
    }

    private fun updateUndoRedoState() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun recordPageJump(fromPage: Int, toPage: Int) {
        pushUndo(UndoableAction.PageJumped(fromPage, toPage))
    }

    private fun findHighlightByBounds(pageIndex: Int, bounds: List<RectF>): PdfHighlight? {
        val state = _uiState.value as? PdfViewerUiState.Ready ?: return null
        return state.pages.getOrNull(pageIndex)?.highlights?.firstOrNull { it.bounds == bounds }
    }

    fun undo() {
        val action = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(action)
        isUndoRedoInProgress = true
        try {
            when (action) {
                is UndoableAction.StrokeAdded ->
                    eraseInkStrokes(action.pageIndex, listOf(action.stroke.id))
                is UndoableAction.StrokesErased ->
                    action.strokes.forEach { addStrokeDirectly(action.pageIndex, it) }
                is UndoableAction.PageJumped ->
                    _pendingScrollToPage.value = action.fromPage
                is UndoableAction.HighlightAdded ->
                    findHighlightByBounds(action.pageIndex, action.bounds)?.let { deleteHighlight(it) }
                is UndoableAction.HighlightDeleted ->
                    addHighlightFromData(action.pageIndex, action.bounds, action.note)
                is UndoableAction.AnnotationEdited ->
                    findHighlightByBounds(action.pageIndex, action.bounds)?.let {
                        setHighlightNote(it, action.oldNote ?: "")
                    }
                is UndoableAction.MetadataChanged ->
                    setMetadata(action.oldTitle, action.oldAuthor, action.oldProjects)
                is UndoableAction.StrokesMoved ->
                    moveInkStrokes(action.pageIndex, action.movedStrokes, action.originalStrokes)
            }
        } finally {
            isUndoRedoInProgress = false
            updateUndoRedoState()
        }
    }

    fun redo() {
        val action = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(action)
        isUndoRedoInProgress = true
        try {
            when (action) {
                is UndoableAction.StrokeAdded ->
                    addStrokeDirectly(action.pageIndex, action.stroke)
                is UndoableAction.StrokesErased ->
                    eraseInkStrokes(action.pageIndex, action.strokes.map { it.id })
                is UndoableAction.PageJumped ->
                    _pendingScrollToPage.value = action.toPage
                is UndoableAction.HighlightAdded ->
                    addHighlightFromData(action.pageIndex, action.bounds, action.note)
                is UndoableAction.HighlightDeleted ->
                    findHighlightByBounds(action.pageIndex, action.bounds)?.let { deleteHighlight(it) }
                is UndoableAction.AnnotationEdited ->
                    findHighlightByBounds(action.pageIndex, action.bounds)?.let {
                        setHighlightNote(it, action.newNote ?: "")
                    }
                is UndoableAction.MetadataChanged ->
                    setMetadata(action.newTitle, action.newAuthor, action.newProjects)
                is UndoableAction.StrokesMoved ->
                    moveInkStrokes(action.pageIndex, action.originalStrokes, action.movedStrokes)
            }
        } finally {
            isUndoRedoInProgress = false
            updateUndoRedoState()
        }
    }

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderMutex = Mutex()
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun launchSave(block: suspend CoroutineScope.() -> Unit): Job =
        saveScope.launch { block() }

    // Pending ink strokes awaiting a batched save (main-thread access only)
    private val pendingInkStrokes = mutableMapOf<Int, MutableList<InkStroke>>() // pageIndex → strokes
    private var strokeSaveJob: Job? = null
    private val STROKE_SAVE_DEBOUNCE_MS = 500L

    private var pendingDeleteJob: Job? = null
    private var deletedPageSnapshot: Triple<Int, PdfPage, List<InkStroke>>? = null

    private var rerenderJob: Job? = null
    private var currentRenderScale = 2f
    private var docUri: Uri? = null
    private var loadedDirUri: Uri? = null
    private var loadedDocId: String? = null
    private var loadedFileName: String = ""
    var firstVisiblePageIndex: Int = 0

    private val _pendingScrollToPage = MutableStateFlow(-1)
    val pendingScrollToPage: StateFlow<Int> = _pendingScrollToPage.asStateFlow()
    fun clearPendingScroll() { _pendingScrollToPage.value = -1 }

    fun onVisiblePageChanged(index: Int) {
        firstVisiblePageIndex = index
        val uri = docUri ?: return
        viewModelScope.launch { prefsRepo.saveLastPage(uri, index) }
    }

    fun loadPdf(dirUri: Uri, docId: String) {
        loadedDirUri = dirUri
        loadedDocId = docId
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
            loadedFileName = fileName
            val lastPage = prefsRepo.getLastPage(uri) ?: 0
            renderPages(dirUri, docId, fileName, lastPage)
        }
    }

    fun insertPage(insertBeforeIndex: Int) {
        val dirUri = loadedDirUri ?: return
        val docId = loadedDocId ?: return
        rerenderJob?.cancel()
        flushPendingInkStrokes()

        // Phase 1: show blank page immediately (no IO)
        val currentState = _uiState.value
        if (currentState is PdfViewerUiState.Ready) {
            val refPage = currentState.pages.getOrElse(insertBeforeIndex) { currentState.pages.last() }
            val blankBitmap = Bitmap.createBitmap(
                refPage.bitmap.width, refPage.bitmap.height, Bitmap.Config.ARGB_8888
            ).also { android.graphics.Canvas(it).drawColor(android.graphics.Color.WHITE) }
            val blankPage = PdfPage(
                bitmap = blankBitmap,
                nativeWidth = refPage.nativeWidth,
                nativeHeight = refPage.nativeHeight,
                links = emptyList(),
                words = emptyList(),
                highlights = emptyList()
            )
            val newPages = currentState.pages.toMutableList()
            newPages.add(insertBeforeIndex.coerceIn(0, newPages.size), blankPage)
            _uiState.value = currentState.copy(pages = newPages)

            // Shift ink stroke page indices for pages at or after insertion point
            _completedInkStrokes.update { map ->
                map.entries.associate { (pageIndex, strokes) ->
                    if (pageIndex >= insertBeforeIndex) pageIndex + 1 to strokes
                    else pageIndex to strokes
                }
            }
            _pendingScrollToPage.value = insertBeforeIndex

            // Phase 2: save to PDF and silently reopen renderer in background
            launchSave {
                val uri = docUri ?: return@launchSave
                val app = getApplication<Application>()
                renderMutex.withLock {
                    renderer?.close(); pfd?.close()
                    renderer = null; pfd = null

                    val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                    val mediaBox = pdDoc.getPage(insertBeforeIndex.coerceIn(0, pdDoc.numberOfPages - 1)).mediaBox
                    val newPage = PDPage(mediaBox)
                    newPage.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
                    if (insertBeforeIndex >= pdDoc.numberOfPages) {
                        pdDoc.addPage(newPage)
                    } else {
                        pdDoc.pages.insertBefore(newPage, pdDoc.getPage(insertBeforeIndex))
                    }
                    pdfRepository.save(pdDoc, uri)
                    pdDoc.close()

                    // Silently reopen renderer — no Loading state, no renderPages()
                    val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                    pfd = newPfd
                    renderer = PdfRenderer(newPfd)
                }
            }

        } else {
            // Fallback: not Ready (Loading/Error) — go through full reload (original behaviour)
            launchSave {
                val uri = docUri ?: return@launchSave
                val app = getApplication<Application>()
                renderMutex.withLock {
                    renderer?.close(); pfd?.close()
                    renderer = null; pfd = null

                    val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                    val mediaBox = pdDoc.getPage(insertBeforeIndex.coerceIn(0, pdDoc.numberOfPages - 1)).mediaBox
                    val newPage = PDPage(mediaBox)
                    newPage.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
                    if (insertBeforeIndex >= pdDoc.numberOfPages) {
                        pdDoc.addPage(newPage)
                    } else {
                        pdDoc.pages.insertBefore(newPage, pdDoc.getPage(insertBeforeIndex))
                    }
                    pdfRepository.save(pdDoc, uri)
                    pdDoc.close()
                }
                withContext(Dispatchers.Main) {
                    _pendingScrollToPage.value = insertBeforeIndex
                    _uiState.value = PdfViewerUiState.Loading
                }
                renderPages(dirUri, docId, loadedFileName)
            }
        }
    }

    fun deletePage(pageIndex: Int) {
        val currentState = _uiState.value as? PdfViewerUiState.Ready ?: return
        if (pageIndex !in currentState.pages.indices) return

        flushPendingInkStrokes()

        // Save snapshot for potential undo
        val inkStrokes = _completedInkStrokes.value[pageIndex] ?: emptyList()
        deletedPageSnapshot = Triple(pageIndex, currentState.pages[pageIndex], inkStrokes)

        // Phase 1: Optimistic UI removal
        val newPages = currentState.pages.toMutableList().also { it.removeAt(pageIndex) }
        _uiState.value = currentState.copy(pages = newPages)
        _completedInkStrokes.update { map ->
            map.entries
                .filter { it.key != pageIndex }
                .associate { (idx, strokes) ->
                    if (idx > pageIndex) idx - 1 to strokes else idx to strokes
                }
        }

        // Phase 2: Delayed disk write — cancelled if undo is triggered
        pendingDeleteJob = saveScope.launch {
            delay(1100)
            val uri = docUri ?: return@launch
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                pdDoc.removePage(pageIndex)
                pdfRepository.save(pdDoc, uri)
                pdDoc.close()

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)
            }
            deletedPageSnapshot = null
        }
    }

    fun cancelDelete() {
        pendingDeleteJob?.cancel()
        pendingDeleteJob = null
        val (pageIndex, page, inkStrokes) = deletedPageSnapshot ?: return
        deletedPageSnapshot = null

        val currentState = _uiState.value as? PdfViewerUiState.Ready ?: return
        val newPages = currentState.pages.toMutableList().also {
            it.add(pageIndex.coerceIn(0, it.size), page)
        }
        _uiState.value = currentState.copy(pages = newPages)
        _completedInkStrokes.update { map ->
            val shifted = map.entries.associate { (idx, strokes) ->
                if (idx >= pageIndex) idx + 1 to strokes else idx to strokes
            }.toMutableMap()
            if (inkStrokes.isNotEmpty()) shifted[pageIndex] = inkStrokes
            shifted
        }
        _pendingScrollToPage.value = pageIndex
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
        // Phase 1: compute bounds and show highlight immediately (no IO)
        val sortedChars = selectedChars.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val lines = groupCharsIntoLines(sortedChars)
        val lineBounds = lines.map { lineChars ->
            val left  = lineChars.minOf { it.bounds.left }
            val right = lineChars.maxOf { it.bounds.right }
            val top   = lineChars.minOf { it.bounds.top - it.bounds.height() * 1.6f }
            val bot   = lineChars.maxOf { it.bounds.top + it.bounds.height() * 0.5f }
            RectF(left, top, right, bot)
        }
        val optimisticHighlight = PdfHighlight(pageIndex, lineBounds, annotationIndex = -1)
        _uiState.update { state ->
            if (state !is PdfViewerUiState.Ready) return@update state
            val newPages = state.pages.toMutableList()
            val page = newPages[pageIndex]
            newPages[pageIndex] = page.copy(highlights = page.highlights + optimisticHighlight)
            state.copy(pages = newPages)
        }

        // Phase 2: save to PDF and update annotationIndex in background
        flushPendingInkStrokes()
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(pageIndex)
                val pageH = pdPage.mediaBox.height

                val quads = FloatArray(lines.size * 8)
                lines.forEachIndexed { i, lineChars ->
                    val left        = lineChars.minOf { it.bounds.left }
                    val right       = lineChars.maxOf { it.bounds.right }
                    val prVisualTop = lineChars.minOf { it.bounds.top - it.bounds.height() * 1.6f }
                    val prVisualBot = lineChars.maxOf { it.bounds.top + it.bounds.height() * 0.5f }
                    val pbTop = pageH - prVisualTop
                    val pbBot = pageH - prVisualBot
                    val base = i * 8
                    quads[base+0] = left;  quads[base+1] = pbTop
                    quads[base+2] = right; quads[base+3] = pbTop
                    quads[base+4] = left;  quads[base+5] = pbBot
                    quads[base+6] = right; quads[base+7] = pbBot
                }
                val allLeft      = selectedChars.minOf { it.bounds.left }
                val allRight     = selectedChars.maxOf { it.bounds.right }
                val allVisualTop = selectedChars.minOf { it.bounds.top - it.bounds.height() * 1.6f }
                val allVisualBot = selectedChars.maxOf { it.bounds.top + it.bounds.height() * 0.5f }
                val ann = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                    quadPoints = quads
                    color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                    rectangle = PDRectangle(allLeft, pageH - allVisualBot, allRight - allLeft, allVisualBot - allVisualTop)
                }
                pdPage.annotations.add(ann)

                pdfRepository.save(pdDoc, uri)

                // Re-extract highlights to get correct annotationIndex (needed for deletion)
                val newHighlights = extractHighlights(pdDoc, pageIndex, pageH)
                pdDoc.close()

                // Reopen renderer/pfd for future rendering
                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)

                // Replace optimistic highlight with real one (correct annotationIndex)
                _uiState.update { state ->
                    if (state !is PdfViewerUiState.Ready) return@update state
                    val newPages = state.pages.toMutableList()
                    newPages[pageIndex] = newPages[pageIndex].copy(highlights = newHighlights)
                    state.copy(pages = newPages)
                }
                withContext(Dispatchers.Main) {
                    pushUndo(UndoableAction.HighlightAdded(pageIndex, lineBounds, null))
                }
            }
        }
    }

    // Builds and adds a single ink annotation to an already-open PDDocument (does NOT save)
    private fun addInkAnnotationToDoc(pdDoc: PDDocument, pageIndex: Int, stroke: InkStroke) {
        val pdPage = pdDoc.getPage(pageIndex)
        val pageW = pdPage.mediaBox.width
        val pageH = pdPage.mediaBox.height

        val normalized = stroke.points
        val coords = FloatArray(normalized.size * 2) { i ->
            val pt = normalized[i / 2]
            if (i % 2 == 0) pt.x * pageW else pageH - pt.y * pageH
        }
        val minX = coords.filterIndexed { i, _ -> i % 2 == 0 }.min()
        val minY = coords.filterIndexed { i, _ -> i % 2 != 0 }.min()
        val maxX = coords.filterIndexed { i, _ -> i % 2 == 0 }.max()
        val maxY = coords.filterIndexed { i, _ -> i % 2 != 0 }.max()

        val strokeWidth = stroke.thickness.multiplier * 1.5f
        val pad = strokeWidth * 0.7f

        val innerList = COSArray().apply { coords.forEach { add(COSFloat(it)) } }
        val outerList = COSArray().apply { add(innerList) }
        val rectArr = COSArray().apply {
            listOf(minX - pad, minY - pad, maxX + pad, maxY + pad).forEach { add(COSFloat(it)) }
        }
        val colorArr = COSArray().apply {
            stroke.color.pdfRgb.forEach { add(COSFloat(it)) }
        }
        val bsDict = COSDictionary().apply {
            setName(COSName.TYPE, "Border")
            setName(COSName.SUBTYPE, "S")
            setItem(COSName.getPDFName("W"), COSFloat(stroke.thickness.multiplier * 1.5f))
        }

        val rgb = stroke.color.pdfRgb
        val apContent = buildString {
            append("q ")
            append("1 J ")  // round line cap
            append("1 j ")  // round line join
            append("%.4f w ".format(strokeWidth))
            append("%.4f %.4f %.4f RG ".format(rgb[0], rgb[1], rgb[2]))
            for (i in coords.indices step 2) {
                val op = if (i == 0) "m" else "l"
                append("%.4f %.4f $op ".format(coords[i], coords[i + 1]))
            }
            append("S Q")
        }.toByteArray()
        val apStream = PDStream(pdDoc)
        apStream.createOutputStream().use { it.write(apContent) }
        val apCos = apStream.cosObject
        apCos.setName(COSName.TYPE, "XObject")
        apCos.setName(COSName.SUBTYPE, "Form")
        apCos.setItem(COSName.BBOX, COSArray().apply {
            listOf(minX - pad, minY - pad, maxX + pad, maxY + pad).forEach { add(COSFloat(it)) }
        })
        val apDict = COSDictionary().apply { setItem(COSName.N, apCos) }

        val annDict = COSDictionary().apply {
            setName(COSName.TYPE, "Annot")
            setName(COSName.SUBTYPE, "Ink")
            setItem(COSName.INKLIST, outerList)
            setItem(COSName.RECT, rectArr)
            setItem(COSName.getPDFName("C"), colorArr)
            setItem(COSName.getPDFName("BS"), bsDict)
            setItem(COSName.AP, apDict)
            setString(COSName.NM, "ink-${stroke.id}")
        }
        pdPage.annotations.add(PDAnnotationUnknown(annDict))
    }

    // Must be called within renderMutex.withLock, with renderer/pfd already closed
    private suspend fun writeInkStrokesToPdf(
        uri: Uri, app: Application, strokesByPage: Map<Int, List<InkStroke>>
    ) {
        val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
        strokesByPage.forEach { (pageIndex, strokes) ->
            strokes.forEach { stroke -> addInkAnnotationToDoc(pdDoc, pageIndex, stroke) }
        }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
        pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return
        renderer = PdfRenderer(pfd!!)
    }

    private fun flushPendingInkStrokes() {
        strokeSaveJob?.cancel()
        strokeSaveJob = null
        val toSave = pendingInkStrokes.mapValues { it.value.toList() }
        pendingInkStrokes.clear()
        if (toSave.isEmpty()) return
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                // Filter out strokes that were erased from the overlay before we got the lock
                val filtered = toSave.mapValues { (pageIndex, strokes) ->
                    val current = _completedInkStrokes.value[pageIndex].orEmpty()
                    strokes.filter { s -> current.any { it.id == s.id } }
                }.filter { it.value.isNotEmpty() }
                if (filtered.isEmpty()) return@withLock
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null
                writeInkStrokesToPdf(uri, app, filtered)
            }
        }
    }

    fun moveInkStrokes(pageIndex: Int, originalStrokes: List<InkStroke>, movedStrokes: List<InkStroke>) {
        _completedInkStrokes.update { map ->
            val origIds = originalStrokes.map { it.id }.toSet()
            val kept = map[pageIndex].orEmpty().filter { it.id !in origIds }
            map + (pageIndex to kept + movedStrokes)
        }
        pushUndo(UndoableAction.StrokesMoved(pageIndex, originalStrokes, movedStrokes))
        // Partition: strokes still pending (not yet in PDF) vs. already in PDF
        val origIds = originalStrokes.map { it.id }.toSet()
        val pendingPage = pendingInkStrokes[pageIndex]
        val pendingMovedIds = if (pendingPage != null)
            origIds.filter { id -> pendingPage.removeAll { it.id == id } }
        else emptyList()
        if (pendingPage != null && pendingPage.isEmpty()) pendingInkStrokes.remove(pageIndex)
        // Re-add moved versions of the pending strokes into the pending batch
        val pendingMovedIdSet = pendingMovedIds.toSet()
        if (pendingMovedIdSet.isNotEmpty()) {
            val movedPending = movedStrokes.filter { it.id in pendingMovedIdSet }
            pendingInkStrokes.getOrPut(pageIndex) { mutableListOf() }.addAll(movedPending)
        }
        val idsInPdf = origIds - pendingMovedIds.toSet()
        if (idsInPdf.isEmpty()) {
            // All moved strokes were pending — no PDF write needed, just re-debounce
            strokeSaveJob?.cancel()
            strokeSaveJob = viewModelScope.launch {
                delay(STROKE_SAVE_DEBOUNCE_MS)
                flushPendingInkStrokes()
            }
            return
        }
        // Flush remaining pending strokes + do the move in one PDF write
        strokeSaveJob?.cancel(); strokeSaveJob = null
        val remainingPending = pendingInkStrokes.mapValues { it.value.toList() }
        pendingInkStrokes.clear()
        val inPdfOriginals = originalStrokes.filter { it.id in idsInPdf }
        val inPdfMoved = movedStrokes.filter { it.id in idsInPdf }
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null
                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                // Add any remaining pending strokes first
                remainingPending.forEach { (pi, strokes) ->
                    val current = _completedInkStrokes.value[pi].orEmpty()
                    strokes.filter { s -> current.any { it.id == s.id } }
                        .forEach { s -> addInkAnnotationToDoc(pdDoc, pi, s) }
                }
                // Then do the move for in-PDF strokes
                val pdPage = pdDoc.getPage(pageIndex)
                val origNames = inPdfOriginals.map { "ink-${it.id}" }.toSet()
                val annotations = pdPage.annotations
                annotations.removeAll(annotations.filter { it.annotationName in origNames })
                for (s in inPdfMoved) addInkAnnotationToDoc(pdDoc, pageIndex, s)
                pdfRepository.save(pdDoc, uri)
                pdDoc.close()
                pfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                renderer = PdfRenderer(pfd!!)
            }
        }
    }

    fun addInkAnnotation(
        pageIndex: Int, points: List<Offset>, displayWidth: Int, displayHeight: Int,
        color: StrokeColor = _penColor.value,
        thickness: StrokeThickness = _penThickness.value
    ): Int {
        val normalized = points.map { Offset(it.x / displayWidth, it.y / displayHeight) }
        val strokeId = nextStrokeId++
        val stroke = InkStroke(strokeId, normalized, color, thickness, roundCap = true)
        _completedInkStrokes.update { map ->
            map + (pageIndex to (map[pageIndex].orEmpty() + stroke))
        }
        pushUndo(UndoableAction.StrokeAdded(pageIndex, stroke))
        pendingInkStrokes.getOrPut(pageIndex) { mutableListOf() }.add(stroke)
        strokeSaveJob?.cancel()
        strokeSaveJob = viewModelScope.launch {
            delay(STROKE_SAVE_DEBOUNCE_MS)
            flushPendingInkStrokes()
        }
        return strokeId
    }

    private fun addStrokeDirectly(pageIndex: Int, stroke: InkStroke) {
        _completedInkStrokes.update { map ->
            map + (pageIndex to (map[pageIndex].orEmpty() + stroke))
        }
        pendingInkStrokes.getOrPut(pageIndex) { mutableListOf() }.add(stroke)
        strokeSaveJob?.cancel()
        strokeSaveJob = viewModelScope.launch {
            delay(STROKE_SAVE_DEBOUNCE_MS)
            flushPendingInkStrokes()
        }
    }

    fun eraseInkStrokes(pageIndex: Int, strokeIds: List<Int>) {
        val erasedStrokes = _completedInkStrokes.value[pageIndex].orEmpty().filter { it.id in strokeIds }
        pushUndo(UndoableAction.StrokesErased(pageIndex, erasedStrokes))
        // Remove from overlay immediately
        _completedInkStrokes.update { map ->
            val remaining = map[pageIndex].orEmpty().filter { it.id !in strokeIds }
            if (remaining.isEmpty()) map - pageIndex else map + (pageIndex to remaining)
        }
        // Partition: strokes still pending (not yet written to PDF) vs. already in PDF
        val strokeIdSet = strokeIds.toSet()
        val pendingPage = pendingInkStrokes[pageIndex]
        val pendingErased = if (pendingPage != null)
            strokeIdSet.filter { id -> pendingPage.removeAll { it.id == id } }
        else emptyList()
        if (pendingPage != null && pendingPage.isEmpty()) pendingInkStrokes.remove(pageIndex)
        val idsInPdf = strokeIdSet - pendingErased.toSet()

        if (idsInPdf.isEmpty()) {
            // All erased strokes were pending — no PDF write needed
            return
        }
        // Flush any remaining pending strokes, then erase in a single PDF write
        strokeSaveJob?.cancel(); strokeSaveJob = null
        val remainingPending = pendingInkStrokes.mapValues { it.value.toList() }
        pendingInkStrokes.clear()
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                // Add any remaining pending strokes first
                remainingPending.forEach { (pi, strokes) ->
                    val current = _completedInkStrokes.value[pi].orEmpty()
                    strokes.filter { s -> current.any { it.id == s.id } }
                        .forEach { s -> addInkAnnotationToDoc(pdDoc, pi, s) }
                }
                // Then erase the strokes already in the PDF
                val pdPage = pdDoc.getPage(pageIndex)
                val namesToRemove = idsInPdf.map { "ink-$it" }.toSet()
                val annotations = pdPage.annotations
                annotations.removeAll(annotations.filter { it.annotationName in namesToRemove })

                pdfRepository.save(pdDoc, uri)
                pdDoc.close()

                reloadPage(uri, app, pageIndex)
            }
        }
    }

    fun copyInkStrokes(strokes: List<InkStroke>) {
        _inkClipboard.value = strokes
    }

    fun pasteInkStrokes(pageIndex: Int, centerNorm: Offset) {
        val clipStrokes = _inkClipboard.value ?: return
        if (clipStrokes.isEmpty()) return
        val allPts = clipStrokes.flatMap { it.points }
        val clipCenter = Offset(
            (allPts.minOf { it.x } + allPts.maxOf { it.x }) / 2f,
            (allPts.minOf { it.y } + allPts.maxOf { it.y }) / 2f
        )
        val dx = centerNorm.x - clipCenter.x
        val dy = centerNorm.y - clipCenter.y
        clipStrokes.forEach { stroke ->
            val newStroke = stroke.copy(
                id = nextStrokeId++,
                points = stroke.points.map { Offset(it.x + dx, it.y + dy) }
            )
            pushUndo(UndoableAction.StrokeAdded(pageIndex, newStroke))
            addStrokeDirectly(pageIndex, newStroke)
        }
    }

    fun deleteHighlight(highlight: PdfHighlight) {
        pushUndo(UndoableAction.HighlightDeleted(highlight.pageIndex, highlight.bounds, highlight.note))
        flushPendingInkStrokes()
        launchSave {
            val uri = docUri ?: return@launchSave
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
                pdfRepository.save(pdDoc, uri)
                pdDoc.close()

                reloadPage(uri, app, highlight.pageIndex)
            }
        }
    }

    private fun addHighlightFromData(pageIndex: Int, bounds: List<RectF>, note: String?) {
        // Optimistic UI update
        val optimisticHighlight = PdfHighlight(pageIndex, bounds, annotationIndex = -1, note = note)
        _uiState.update { state ->
            if (state !is PdfViewerUiState.Ready) return@update state
            val newPages = state.pages.toMutableList()
            val page = newPages[pageIndex]
            newPages[pageIndex] = page.copy(highlights = page.highlights + optimisticHighlight)
            state.copy(pages = newPages)
        }
        flushPendingInkStrokes()
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val pdPage = pdDoc.getPage(pageIndex)
                val pageH = pdPage.mediaBox.height

                // Reconstruct quad points from stored bounds (PR space → PDF space)
                val quads = FloatArray(bounds.size * 8)
                bounds.forEachIndexed { i, rect ->
                    val pbTop = pageH - rect.top
                    val pbBot = pageH - rect.bottom
                    val base = i * 8
                    quads[base+0] = rect.left;  quads[base+1] = pbTop
                    quads[base+2] = rect.right; quads[base+3] = pbTop
                    quads[base+4] = rect.left;  quads[base+5] = pbBot
                    quads[base+6] = rect.right; quads[base+7] = pbBot
                }
                val allLeft   = bounds.minOf { it.left }
                val allRight  = bounds.maxOf { it.right }
                val allTop    = bounds.minOf { it.top }
                val allBottom = bounds.maxOf { it.bottom }
                val pbAllTop  = pageH - allTop
                val pbAllBot  = pageH - allBottom

                val ann = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
                    quadPoints = quads
                    color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                    rectangle = PDRectangle(allLeft, pbAllBot, allRight - allLeft, pbAllTop - pbAllBot)
                    if (!note.isNullOrBlank()) contents = note
                }
                pdPage.annotations.add(ann)

                pdfRepository.save(pdDoc, uri)
                val newHighlights = extractHighlights(pdDoc, pageIndex, pageH)
                pdDoc.close()

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)

                _uiState.update { state ->
                    if (state !is PdfViewerUiState.Ready) return@update state
                    val newPages = state.pages.toMutableList()
                    newPages[pageIndex] = newPages[pageIndex].copy(highlights = newHighlights)
                    state.copy(pages = newPages)
                }
            }
        }
    }

    fun setHighlightNote(highlight: PdfHighlight, note: String) {
        pushUndo(UndoableAction.AnnotationEdited(
            highlight.pageIndex, highlight.bounds,
            oldNote = highlight.note,
            newNote = note.ifBlank { null }
        ))
        flushPendingInkStrokes()
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val ann = pdDoc.getPage(highlight.pageIndex)
                    .annotations.getOrNull(highlight.annotationIndex)
                if (ann != null) {
                    if (note.isBlank()) ann.getCOSObject().removeItem(COSName.CONTENTS)
                    else ann.contents = note
                }
                pdfRepository.save(pdDoc, uri)
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

                    val roundCap = cosObj.getDictionaryObject(COSName.AP) != null
                    if (points.isNotEmpty()) result.add(InkStroke(id, points, strokeColor, strokeThickness, roundCap))
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
                    val note = ann.contents?.takeIf { it.isNotBlank() }
                    PdfHighlight(pageIndex, rects, realIdx, note)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractOutline(pdDoc: PDDocument): List<OutlineItem> {
        val result = mutableListOf<OutlineItem>()
        val root = pdDoc.documentCatalog.documentOutline ?: return result
        fun traverse(item: PDOutlineItem?, level: Int) {
            var current = item
            while (current != null) {
                val title = current.title.orEmpty().trim()
                val dest = try { current.destination } catch (e: Exception) { null }
                    ?: try { (current.action as? PDActionGoTo)?.destination } catch (e: Exception) { null }
                val resolvedDest: PDPageDestination? = when (dest) {
                    is PDPageDestination -> dest
                    is PDNamedDestination -> try {
                        pdDoc.documentCatalog.findNamedDestinationPage(dest)
                    } catch (e: Exception) { null }
                    else -> null
                }
                val pageIndex = resolvedDest?.retrievePageNumber() ?: -1
                if (title.isNotEmpty() && pageIndex >= 0) {
                    result.add(OutlineItem(title, pageIndex, level))
                }
                traverse(current.firstChild, level + 1)
                current = current.nextSibling
            }
        }
        traverse(root.firstChild, 0)
        return result.mapIndexed { i, item ->
            item.copy(hasChildren = i + 1 < result.size && result[i + 1].level > result[i].level)
        }
    }

    private suspend fun renderPages(dirUri: Uri, docId: String, fileName: String, initialPage: Int = 0): Unit =
        withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                PDFBoxResourceLoader.init(app)

                val uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                this@PdfViewerViewModel.docUri = uri

                // Hold the file write lock while opening the file to prevent reading a
                // partially-written file if a background save is in progress.
                val (newPfd, pdDoc) = PdfRepository.fileWriteLockFor(uri).withLock {
                    val pfd = app.contentResolver.openFileDescriptor(uri, "r")
                        ?: run {
                            withContext(Dispatchers.Main) {
                                _uiState.value = PdfViewerUiState.Error("Could not open file")
                            }
                            return@withLock null
                        }
                    pfd to PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                } ?: return@withContext

                pfd?.close()
                renderer?.close()
                pfd = newPfd
                val newRenderer = PdfRenderer(newPfd)
                renderer = newRenderer
                currentRenderScale = 2f
                val title    = pdDoc.documentInformation?.title?.takeIf  { it.isNotBlank() } ?: ""
                val author   = pdDoc.documentInformation?.author?.takeIf { it.isNotBlank() } ?: ""
                val projects = PdfRepository.readProjectsFromXmp(pdDoc)
                withContext(Dispatchers.Main) {
                    _displayTitle.value = title.ifBlank { fileName }
                    _displayAuthor.value = author
                    _displayProjects.value = projects
                }

                // Extract outline (fast — just traverses bookmark tree)
                val outline = extractOutline(pdDoc)
                withContext(Dispatchers.Main) { _outline.value = outline }

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
                    _uiState.value = PdfViewerUiState.Ready(pages, title, author, projects)
                    if (initialPage > 0) _pendingScrollToPage.value = initialPage
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
                    _uiState.value = PdfViewerUiState.Ready(pagesWithHighlights, title, author, projects)
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
                val uri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                val backup = pdfRepository.backupFileFor(uri)
                withContext(Dispatchers.Main) {
                    _uiState.value = if (backup.exists()) {
                        PdfViewerUiState.CorruptedWithBackup(backup, uri)
                    } else {
                        PdfViewerUiState.Error(e.message ?: "Failed to render PDF")
                    }
                }
            }
        }

    fun setMetadata(newTitle: String, newAuthor: String, newProjects: List<String>) {
        pushUndo(UndoableAction.MetadataChanged(
            oldTitle = _displayTitle.value,
            newTitle = newTitle,
            oldAuthor = _displayAuthor.value,
            newAuthor = newAuthor,
            oldProjects = _displayProjects.value,
            newProjects = newProjects
        ))
        flushPendingInkStrokes()
        launchSave {
            val uri = docUri ?: return@launchSave
            val app = getApplication<Application>()
            renderMutex.withLock {
                renderer?.close(); pfd?.close()
                renderer = null; pfd = null

                val pdDoc = PDDocument.load(app.contentResolver.openInputStream(uri)!!)
                val info = pdDoc.documentInformation
                info.title  = newTitle
                info.author = newAuthor
                pdDoc.documentInformation = info  // re-attach in case no /Info dict existed
                PdfRepository.writeProjectsToXmp(pdDoc, newProjects)
                pdfRepository.save(pdDoc, uri)
                pdDoc.close()

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)
            }
            val state = _uiState.value as? PdfViewerUiState.Ready ?: return@launchSave
            withContext(Dispatchers.Main) {
                _displayTitle.value = newTitle
                _displayAuthor.value = newAuthor
                _displayProjects.value = newProjects
                _uiState.value = state.copy(title = newTitle, author = newAuthor, projects = newProjects)
            }
        }
    }

    fun restoreFromBackup(backupFile: File, uri: Uri) {
        val dirUri = loadedDirUri ?: return
        val docId = loadedDocId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _uiState.value = PdfViewerUiState.Loading }
            backupFile.inputStream().use { input ->
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use {
                    input.copyTo(it)
                }
            }
            backupFile.delete()
            renderPages(dirUri, docId, loadedFileName)
        }
    }

    override fun onCleared() {
        flushPendingInkStrokes()
        super.onCleared()
        saveScope.launch {
            renderMutex.withLock {
                renderer?.close()
                pfd?.close()
                renderer = null
                pfd = null
            }
            saveScope.cancel()
        }
    }

}
