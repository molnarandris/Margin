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
import io.github.molnarandris.margin.data.PdfRepository
import io.github.molnarandris.margin.data.PreferencesRepository
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
    data class Ready(val pages: List<PdfPage>, val title: String = "", val authors: List<String> = emptyList(), val projects: List<String> = emptyList()) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
    data class CorruptedWithBackup(val backupFile: File, val uri: Uri) : PdfViewerUiState()
}

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Loading)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private val _displayTitle = MutableStateFlow("")
    val displayTitle: StateFlow<String> = _displayTitle.asStateFlow()

    private val _displayAuthors = MutableStateFlow<List<String>>(emptyList())
    val displayAuthors: StateFlow<List<String>> = _displayAuthors.asStateFlow()

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
    private val pdfEditor = PdfEditor(application, pdfRepository)

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
    val previousDocParams: StateFlow<Pair<Uri, String>?> = PdfRepository.previousDocParams

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
                    setMetadata(action.oldTitle, action.oldAuthors, action.oldProjects)
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
                    setMetadata(action.newTitle, action.newAuthors, action.newProjects)
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
    private val STROKE_SAVE_DEBOUNCE_MS = 1000L

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
            pdfRepository.recordOpen(dirUri, uri)
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

                    pdfEditor.insertPageInDoc(uri, insertBeforeIndex)

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

                    pdfEditor.insertPageInDoc(uri, insertBeforeIndex)
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

                pdfEditor.deletePageFromDoc(uri, pageIndex)

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
        val lines = pdfEditor.groupCharsIntoLines(sortedChars)
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

                val newHighlights = pdfEditor.addHighlightAnnotation(uri, pageIndex, lineBounds, null)

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
                pdfEditor.writeInkStrokesToPdf(uri, filtered)
                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)
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

                val filteredPending = remainingPending.mapValues { (pi, strokes) ->
                    val current = _completedInkStrokes.value[pi].orEmpty()
                    strokes.filter { s -> current.any { it.id == s.id } }
                }.filter { it.value.isNotEmpty() }

                pdfEditor.removeInkAnnotationsAndAdd(
                    uri = uri,
                    pageIndex = pageIndex,
                    strokeIdsToRemove = inPdfOriginals.map { it.id }.toSet(),
                    strokesToAdd = inPdfMoved,
                    alsoWritePending = filteredPending
                )

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)
            }
        }
    }

    fun addInkAnnotation(
        pageIndex: Int, points: List<Offset>, displayWidth: Int, displayHeight: Int,
        color: StrokeColor = _penColor.value,
        thickness: StrokeThickness = _penThickness.value
    ): Int {
        val simplified = rdpSimplify(points, epsilon = 0.5f)
        val normalized = simplified.map { Offset(it.x / displayWidth, it.y / displayHeight) }
        val strokeId = nextStrokeId++
        val stroke = InkStroke(strokeId, normalized, color, thickness, roundCap = true, timestamp = System.currentTimeMillis())
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

                val filteredPending = remainingPending.mapValues { (pi, strokes) ->
                    val current = _completedInkStrokes.value[pi].orEmpty()
                    strokes.filter { s -> current.any { it.id == s.id } }
                }.filter { it.value.isNotEmpty() }

                pdfEditor.removeInkAnnotationsAndAdd(
                    uri = uri,
                    pageIndex = pageIndex,
                    strokeIdsToRemove = idsInPdf,
                    strokesToAdd = emptyList(),
                    alsoWritePending = filteredPending
                )

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

                pdfEditor.deleteHighlightAnnotation(uri, highlight.pageIndex, highlight.annotationIndex)

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

                val newHighlights = pdfEditor.addHighlightAnnotation(uri, pageIndex, bounds, note)

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

                pdfEditor.setHighlightNoteInPdf(uri, highlight.pageIndex, highlight.annotationIndex, note)

                reloadPage(uri, app, highlight.pageIndex)
            }
        }
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
            val (words, highlights) = pdfEditor.loadPageData(uri, pageIndex, page.height.toFloat())

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
                val title    = pdDoc.documentInformation?.title?.takeIf { it.isNotBlank() } ?: ""
                val authors  = pdDoc.documentInformation?.author
                    ?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
                val projects = PdfRepository.readProjectsFromXmp(pdDoc)
                withContext(Dispatchers.Main) {
                    _displayTitle.value = title.ifBlank { fileName }
                    _displayAuthors.value = authors
                    _displayProjects.value = projects
                }

                // Extract outline (fast — just traverses bookmark tree)
                val outline = pdfEditor.extractOutline(pdDoc)
                withContext(Dispatchers.Main) { _outline.value = outline }

                // --- PHASE 1: render initial page immediately, placeholders for the rest ---
                fun extractLinks(page: PdfRenderer.Page): List<PdfLink> {
                    val links = mutableListOf<PdfLink>()
                    page.getLinkContents().forEach { links.add(PdfLink(it.bounds, LinkTarget.Url(it.uri))) }
                    page.getGotoLinks().forEach { dest ->
                        links.add(PdfLink(dest.bounds, LinkTarget.Goto(dest.destination.pageNumber,
                            dest.destination.xCoordinate, dest.destination.yCoordinate, dest.destination.zoom)))
                    }
                    return links
                }

                val firstIndex = initialPage.coerceIn(0, newRenderer.pageCount - 1)

                val pages: MutableList<PdfPage> = renderMutex.withLock {
                    (0 until newRenderer.pageCount).map { index ->
                        newRenderer.openPage(index).use { page ->
                            if (index == firstIndex) {
                                val bitmap = Bitmap.createBitmap(
                                    (page.width * 2f).toInt(),
                                    (page.height * 2f).toInt(),
                                    Bitmap.Config.ARGB_8888
                                )
                                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                PdfPage(bitmap, page.width, page.height, extractLinks(page), emptyList(), emptyList())
                            } else {
                                // Placeholder: real dimensions, small bitmap — replaced in background below
                                PdfPage(Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888),
                                    page.width, page.height, emptyList(), emptyList(), emptyList())
                            }
                        }
                    }.toMutableList()
                }

                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Ready(pages, title, authors, projects)
                    if (initialPage > 0) _pendingScrollToPage.value = initialPage
                }

                // Show highlights and ink strokes on the initial page before rendering the rest
                val firstHighlights = pdfEditor.extractHighlights(pdDoc, firstIndex, pages[firstIndex].nativeHeight.toFloat())
                val firstInkStrokes = pdfEditor.extractInkStrokes(pdDoc, firstIndex, pages[firstIndex].nativeWidth.toFloat(), pages[firstIndex].nativeHeight.toFloat())
                if (firstHighlights.isNotEmpty() || firstInkStrokes.isNotEmpty()) {
                    if (firstHighlights.isNotEmpty())
                        pages[firstIndex] = pages[firstIndex].copy(highlights = firstHighlights)
                    val state = _uiState.value as? PdfViewerUiState.Ready
                    if (state != null) {
                        withContext(Dispatchers.Main) {
                            _uiState.value = state.copy(pages = pages.toList())
                            if (firstInkStrokes.isNotEmpty()) {
                                _completedInkStrokes.value = mapOf(firstIndex to firstInkStrokes)
                                val maxId = firstInkStrokes.maxOfOrNull { it.id } ?: -1
                                if (maxId >= nextStrokeId) nextStrokeId = maxId + 1
                            }
                        }
                    }
                }

                // Render remaining pages in background, outward from firstIndex so nearby pages load first
                val remainingIndices = buildList {
                    var lo = firstIndex - 1
                    var hi = firstIndex + 1
                    while (lo >= 0 || hi < pages.size) {
                        if (hi < pages.size) add(hi++)
                        if (lo >= 0) add(lo--)
                    }
                }
                for (index in remainingIndices) {
                    renderMutex.withLock {
                        newRenderer.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                (page.width * 2f).toInt(),
                                (page.height * 2f).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages[index] = pages[index].copy(bitmap = bitmap, links = extractLinks(page))
                        }
                    }
                    val state = _uiState.value as? PdfViewerUiState.Ready ?: continue
                    withContext(Dispatchers.Main) {
                        _uiState.value = state.copy(pages = pages.toList())
                    }
                }

                val pageCount = pages.size

                // Extract all highlights first — fast, not coupled to word extraction
                val allHighlights = (0 until pageCount).map { i ->
                    pdfEditor.extractHighlights(pdDoc, i, pages[i].nativeHeight.toFloat())
                }
                val pagesWithHighlights = pages.mapIndexed { i, page ->
                    page.copy(highlights = allHighlights[i])
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Ready(pagesWithHighlights, title, authors, projects)
                }

                // Extract ink strokes from saved annotations and populate overlay
                val allInkStrokes = (0 until pageCount).associate { i ->
                    i to pdfEditor.extractInkStrokes(pdDoc, i, pages[i].nativeWidth.toFloat(), pages[i].nativeHeight.toFloat())
                }.filter { it.value.isNotEmpty() }
                withContext(Dispatchers.Main) {
                    _completedInkStrokes.value = allInkStrokes
                    val maxId = allInkStrokes.values.flatten().maxOfOrNull { it.id } ?: -1
                    if (maxId >= nextStrokeId) nextStrokeId = maxId + 1
                }

                // Extract words (slow) and emit final state
                val allWords = pdfEditor.extractAllWords(pdDoc, pageCount)
                pdDoc.close()

                val enrichedPages = pagesWithHighlights.mapIndexed { i, page ->
                    page.copy(words = allWords[i])
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = PdfViewerUiState.Ready(enrichedPages, title, authors)
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

    fun setMetadata(newTitle: String, newAuthors: List<String>, newProjects: List<String>) {
        pushUndo(UndoableAction.MetadataChanged(
            oldTitle = _displayTitle.value,
            newTitle = newTitle,
            oldAuthors = _displayAuthors.value,
            newAuthors = newAuthors,
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

                pdfEditor.setMetadataInDoc(uri, newTitle, newAuthors, newProjects)
                pdfRepository.syncMetadataToDb(uri, newTitle, newAuthors, newProjects)

                val newPfd = app.contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
                pfd = newPfd
                renderer = PdfRenderer(newPfd)
            }
            val state = _uiState.value as? PdfViewerUiState.Ready ?: return@launchSave
            withContext(Dispatchers.Main) {
                _displayTitle.value = newTitle
                _displayAuthors.value = newAuthors
                _displayProjects.value = newProjects
                _uiState.value = state.copy(title = newTitle, authors = newAuthors, projects = newProjects)
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
