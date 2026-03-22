package io.github.molnarandris.margin.ui.pdfviewer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.WindowInsets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

private data class DestinationHighlight(val pageIndex: Int, val x: Float, val y: Float)
private data class JumpOrigin(val pageIndex: Int, val scrollOffset: Int, val highlightX: Float, val highlightY: Float)
private data class PageTextSelection(
    val pageIndex: Int,
    val selectedChars: List<TextChar>,
    val existingHighlight: PdfHighlight? = null
)

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun PdfViewerScreen(
    dirUri: Uri,
    docId: String,
    onBack: () -> Unit,
    viewModel: PdfViewerViewModel = viewModel()
) {
    LaunchedEffect(dirUri, docId) {
        viewModel.loadPdf(dirUri, docId)
    }

    val uiState by viewModel.uiState.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val completedInkStrokes by viewModel.completedInkStrokes.collectAsState()
    val penColor by viewModel.penColor.collectAsState()
    val penThickness by viewModel.penThickness.collectAsState()
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val pdfTitle  by viewModel.displayTitle.collectAsState()
    val pdfAuthor by viewModel.displayAuthor.collectAsState()
    var isEditDialogVisible by remember { mutableStateOf(false) }
    var titleEditText  by remember { mutableStateOf("") }
    var authorEditText by remember { mutableStateOf("") }
    var currentPage by remember { mutableStateOf(0) }

    var topBarVisible by remember { mutableStateOf(true) }
    val view = LocalView.current
    val gestureZonePx = remember { 32 * view.resources.displayMetrics.density }

    LaunchedEffect(searchQuery) {
        viewModel.search(searchQuery)
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    if (isEditDialogVisible) {
        AlertDialog(
            onDismissRequest = { isEditDialogVisible = false },
            title = { Text("Edit Document Info") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = titleEditText,
                        onValueChange = { titleEditText = it },
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = authorEditText,
                        onValueChange = { authorEditText = it },
                        label = { Text("Author") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setMetadata(titleEditText, authorEditText)
                    isEditDialogVisible = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { isEditDialogVisible = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        containerColor = Color(0xFFE0E0E0),
        topBar = { if (topBarVisible) Column {
            val density = LocalDensity.current
            val defaultInsets = TopAppBarDefaults.windowInsets
            val reducedInsets = WindowInsets(
                top = (defaultInsets.getTop(density) - with(density) { 8.dp.roundToPx() }).coerceAtLeast(0)
            )
            TopAppBar(
                expandedHeight = 56.dp,
                windowInsets = reducedInsets,
                title = {
                    if (isSearchVisible) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Search…") },
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester)
                        )
                    } else {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(0.dp),
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = {
                                    titleEditText  = pdfTitle
                                    authorEditText = pdfAuthor
                                    isEditDialogVisible = true
                                })
                            }
                        ) {
                            Text(
                                text = if (pdfTitle.isNotEmpty()) pdfTitle else "No Title",
                                fontSize = 16.sp,
                                lineHeight = 18.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val totalPages = (uiState as? PdfViewerUiState.Ready)?.pages?.size ?: 0
                            val pageInfo = if (totalPages > 0) "${currentPage + 1} / $totalPages" else ""
                            val subtitle = listOfNotNull(
                                pdfAuthor.ifEmpty { null },
                                pageInfo.ifEmpty { null }
                            ).joinToString("  ·  ")
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    fontSize = 12.sp,
                                    lineHeight = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (isSearchVisible) {
                        val matches = searchState.matches
                        val currentIndex = searchState.currentIndex
                        if (matches.isNotEmpty()) {
                            Text("${currentIndex + 1} / ${matches.size}")
                        }
                        IconButton(onClick = { viewModel.prevMatch() }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
                        }
                        IconButton(onClick = { viewModel.nextMatch() }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
                        }
                        IconButton(onClick = {
                            isSearchVisible = false
                            searchQuery = ""
                            viewModel.clearSearch()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    } else {
                        IconButton(onClick = { isSearchVisible = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                    }
                }
            )
            PenToolbar(
                selectedColor = penColor,
                selectedThickness = penThickness,
                onColorSelected = { viewModel.setPenColor(it) },
                onThicknessSelected = { viewModel.setPenThickness(it) }
            )
        } },
    ) { innerPadding ->
        when (val state = uiState) {
            is PdfViewerUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is PdfViewerUiState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "Error: ${state.message}")
                }
            }

            is PdfViewerUiState.Ready -> {
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var destinationHighlight by remember { mutableStateOf<DestinationHighlight?>(null) }
                var jumpOrigin by remember { mutableStateOf<JumpOrigin?>(null) }
                var textSelection by remember { mutableStateOf<PageTextSelection?>(null) }
                // Used during handle dragging so we don't update textSelection on every event
                var dragChars by remember { mutableStateOf<List<TextChar>?>(null) }
                var isDraggingHandle by remember { mutableStateOf(false) }
                var popupHeightPx by remember { mutableStateOf(0) }
                var viewportHeightPx by remember { mutableFloatStateOf(0f) }
                var offsetY by remember { mutableFloatStateOf(0f) }
                val currentSelectionRef = rememberUpdatedState(textSelection)
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current
                val clipboardManager = LocalClipboardManager.current

                // Helper: group chars into lines by proximity of their top coordinates.
                fun groupIntoLines(chars: List<TextChar>): List<List<TextChar>> {
                    if (chars.isEmpty()) return emptyList()
                    val threshold = chars.map { it.bounds.height() }.average().toFloat() * 0.5f
                    val lines = mutableListOf<MutableList<TextChar>>()
                    for (char in chars.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
                        val line = lines.firstOrNull { kotlin.math.abs(it.first().bounds.top - char.bounds.top) < threshold }
                        if (line != null) line.add(char) else lines.add(mutableListOf(char))
                    }
                    return lines
                }

                // Helper: chars in PDFBox reading order between start and end (inclusive).
                // allChars retains the extraction order, which handles multi-column correctly.
                fun charsFrom(start: TextChar, end: TextChar, allChars: List<TextChar>): List<TextChar> {
                    val startIdx = allChars.indexOf(start).takeIf { it >= 0 } ?: return listOf(start)
                    val endIdx   = allChars.indexOf(end).takeIf   { it >= 0 } ?: return listOf(end)
                    val lo = minOf(startIdx, endIdx)
                    val hi = maxOf(startIdx, endIdx)
                    return allChars.subList(lo, hi + 1)
                }

                BackHandler(enabled = jumpOrigin != null) {
                    val origin = jumpOrigin!!
                    jumpOrigin = null
                    scale = 1f; offsetX = 0f; offsetY = 0f
                    currentPage = origin.pageIndex
                    coroutineScope.launch {
                        destinationHighlight = DestinationHighlight(origin.pageIndex, origin.highlightX, origin.highlightY)
                        delay(500)
                        destinationHighlight = null
                    }
                }

                LaunchedEffect(Unit) {
                    snapshotFlow { scale to currentPage }
                        .debounce(300)
                        .collect { (currentScale, page) ->
                            viewModel.updateRenderScale(currentScale, listOf(page))
                        }
                }

                // Clear selection and update visible page when page changes (zoom preserved for swipe)
                LaunchedEffect(currentPage) {
                    textSelection = null
                    viewModel.onVisiblePageChanged(currentPage)
                }

                val density = LocalDensity.current
                val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                val marginPx = with(density) { 8.dp.toPx() }
                val contentWidthDp = with(density) { (screenWidthPx * scale).toDp() }

                // Scroll to current search match (both vertical and horizontal)
                LaunchedEffect(searchState.currentIndex) {
                    val match = searchState.matches.getOrNull(searchState.currentIndex) ?: return@LaunchedEffect
                    val page = state.pages.getOrNull(match.pageIndex) ?: return@LaunchedEffect
                    val displayedPageWidth = screenWidthPx * scale - 2 * marginPx

                    val matchCenterX = (match.wordBounds.minOf { it.left } + match.wordBounds.maxOf { it.right }) / 2f
                    val destXPx = matchCenterX / page.nativeWidth * displayedPageWidth
                    val newOffsetX = screenWidthPx * scale / 2f - marginPx - destXPx
                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                    offsetX = if (scale > 1f) newOffsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f

                    scale = 1f; offsetX = 0f; offsetY = 0f
                    currentPage = match.pageIndex
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .let { if (topBarVisible) it.padding(innerPadding) else it }
                        .background(Color(0xFFE0E0E0))
                        .onSizeChanged { viewportHeightPx = it.height.toFloat() }
                        .pointerInput(screenWidthPx, marginPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startedFromLeftEdge = down.position.x < gestureZonePx
                                var wasMultiTouch = false
                                var everMultiTouch = false
                                var singleTouchAxis = 0
                                var accumDx = 0f
                                var accumDy = 0f
                                var totalDx = 0f
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val pg = state.pages.getOrNull(currentPage)
                                    if (event.changes.count { it.pressed } >= 2) {
                                        if (wasMultiTouch) {
                                            val zoomChange = event.calculateZoom()
                                            val panChange = event.calculatePan()
                                            val touches = event.changes.filter { it.pressed }
                                            val span = if (touches.size >= 2)
                                                (touches[0].position - touches[1].position).getDistance()
                                            else Float.MAX_VALUE
                                            val spanChangePx = span * kotlin.math.abs(zoomChange - 1f)
                                            val closeFingers = span < 250f
                                            if (!closeFingers || spanChangePx > 8f) {
                                                val centroid = event.calculateCentroid(useCurrent = false)
                                                val minScale = if (pg != null && viewportHeightPx > 0f)
                                                    (viewportHeightPx * pg.nativeWidth / (screenWidthPx * pg.nativeHeight)).coerceAtMost(1f)
                                                else 1f
                                                val newScale = (scale * zoomChange).coerceIn(minScale, 5f)
                                                val actualZoom = newScale / scale
                                                val contentLeft = screenWidthPx * (1f - scale) / 2f + offsetX
                                                val newContentLeft = centroid.x * (1f - actualZoom) +
                                                        contentLeft * actualZoom + panChange.x
                                                val rawOffsetX = newContentLeft - screenWidthPx * (1f - newScale) / 2f
                                                scale = newScale
                                                offsetX = if (newScale > 1f) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (newScale - 1f) / 2f
                                                    rawOffsetX.coerceIn(-maxOffsetX, maxOffsetX)
                                                } else 0f
                                                if (pg != null) {
                                                    val maxOffsetY = ((screenWidthPx * newScale - 2 * marginPx) *
                                                        pg.nativeHeight / pg.nativeWidth -
                                                        viewportHeightPx).coerceAtLeast(0f) / 2f
                                                    offsetY = (offsetY - panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                                }
                                            } else {
                                                offsetX = if (scale > 1f) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                    (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                                } else 0f
                                                if (pg != null) {
                                                    val maxOffsetY = ((screenWidthPx * scale - 2 * marginPx) *
                                                        pg.nativeHeight / pg.nativeWidth -
                                                        viewportHeightPx).coerceAtLeast(0f) / 2f
                                                    offsetY = (offsetY - panChange.y).coerceIn(-maxOffsetY, maxOffsetY)
                                                }
                                            }
                                        }
                                        wasMultiTouch = true
                                        everMultiTouch = true
                                        singleTouchAxis = 0
                                        event.changes.forEach { it.consume() }
                                    } else if (!everMultiTouch && !isDraggingHandle) {
                                        val change = event.changes.firstOrNull { it.type != PointerType.Stylus }
                                        if (change != null) {
                                            val dx = change.position.x - change.previousPosition.x
                                            val dy = change.position.y - change.previousPosition.y
                                            if (singleTouchAxis == 0) {
                                                accumDx += dx
                                                accumDy += dy
                                                if (accumDx * accumDx + accumDy * accumDy > 64f)
                                                    singleTouchAxis = if (kotlin.math.abs(accumDx) > kotlin.math.abs(accumDy)) 1 else -1
                                            }
                                            if (scale > 1f) {
                                                if (singleTouchAxis == 1) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                    offsetX = (offsetX + dx).coerceIn(-maxOffsetX, maxOffsetX)
                                                    change.consume()
                                                }
                                                if (singleTouchAxis == -1 && pg != null) {
                                                    val pageH = (screenWidthPx * scale - 2 * marginPx) *
                                                        pg.nativeHeight / pg.nativeWidth
                                                    val maxOffsetY = ((pageH - viewportHeightPx) / 2f).coerceAtLeast(0f)
                                                    offsetY = (offsetY - dy).coerceIn(-maxOffsetY, maxOffsetY)
                                                    change.consume()
                                                }
                                            } else if (singleTouchAxis == -1 && pg != null) {
                                                val pageH = (screenWidthPx * scale - 2 * marginPx) *
                                                    pg.nativeHeight / pg.nativeWidth
                                                val maxOffsetY = ((pageH - viewportHeightPx) / 2f).coerceAtLeast(0f)
                                                if (maxOffsetY > 0f) {
                                                    offsetY = (offsetY - dy).coerceIn(-maxOffsetY, maxOffsetY)
                                                    change.consume()
                                                }
                                            }
                                            if (singleTouchAxis == 1 && scale <= 1f && !startedFromLeftEdge) {
                                                totalDx += dx
                                                change.consume()
                                            }
                                        }
                                    } else {
                                        wasMultiTouch = false
                                    }
                                } while (event.changes.any { it.pressed })
                                // After gesture ends: if horizontal swipe at scale<=1, change page instantly
                                if (singleTouchAxis == 1 && scale <= 1f && !startedFromLeftEdge) {
                                    val threshold = with(density) { 60.dp.toPx() }
                                    fun maxOffsetYForPage(pg: PdfPage): Float {
                                        val pageH = (screenWidthPx * scale - 2 * marginPx) * pg.nativeHeight / pg.nativeWidth
                                        return ((pageH - viewportHeightPx) / 2f).coerceAtLeast(0f)
                                    }
                                    if (totalDx < -threshold && currentPage < state.pages.size - 1) {
                                        currentPage++
                                        val pg = state.pages.getOrNull(currentPage)
                                        offsetY = if (pg != null) -maxOffsetYForPage(pg) else 0f
                                    } else if (totalDx > threshold && currentPage > 0) {
                                        currentPage--
                                        val pg = state.pages.getOrNull(currentPage)
                                        offsetY = if (pg != null) maxOffsetYForPage(pg) else 0f
                                    }
                                }
                            }
                        }
                ) {
                    val page = state.pages.getOrNull(currentPage) ?: return@Box
                    val pageDisplayHeightDp = with(density) {
                        (screenWidthPx * scale * page.nativeHeight / page.nativeWidth).toDp()
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                            shape = RectangleShape,
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            modifier = Modifier
                                .requiredWidth(contentWidthDp)
                                .requiredHeight(pageDisplayHeightDp)
                                .offset { IntOffset(offsetX.roundToInt(), -offsetY.roundToInt()) }
                        ) {
                            PageContent(
                                page = page,
                                index = currentPage,
                                completedInkStrokes = completedInkStrokes,
                                penColor = penColor,
                                penThickness = penThickness,
                                searchState = searchState,
                                textSelection = textSelection,
                                dragChars = dragChars,
                                destinationHighlight = destinationHighlight,
                                popupHeightPx = popupHeightPx,
                                density = density,
                                currentSelectionRef = currentSelectionRef,
                                onBarsVisibleToggle = { topBarVisible = !topBarVisible },
                                onTextSelectionChanged = { textSelection = it },
                                onDragCharsChanged = { dragChars = it },
                                onIsDraggingHandleChanged = { isDraggingHandle = it },
                                onPopupHeightPxChanged = { popupHeightPx = it },
                                onAddHighlight = { viewModel.addHighlight(it.pageIndex, it.selectedChars) },
                                onDeleteHighlight = { viewModel.deleteHighlight(it) },
                                onCopy = { clipboardManager.setText(AnnotatedString(it)) },
                                onEraseInkStrokes = { pageIdx, ids -> viewModel.eraseInkStrokes(pageIdx, ids) },
                                onAddInkAnnotation = { pageIdx, stroke, w, h -> viewModel.addInkAnnotation(pageIdx, stroke, w, h) },
                                onLinkTap = { target, linkBoundsFirstRect ->
                                    when (target) {
                                        is LinkTarget.Url -> context.startActivity(Intent(Intent.ACTION_VIEW, target.uri))
                                        is LinkTarget.Goto -> coroutineScope.launch {
                                            jumpOrigin = JumpOrigin(
                                                pageIndex = currentPage,
                                                scrollOffset = 0,
                                                highlightX = linkBoundsFirstRect.centerX(),
                                                highlightY = linkBoundsFirstRect.centerY()
                                            )
                                            val displayedPageWidth = screenWidthPx * scale - 2 * marginPx
                                            val destXPx = target.x / page.nativeWidth * displayedPageWidth
                                            val newOffsetX = screenWidthPx * scale / 2f - marginPx - destXPx
                                            val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                            offsetX = if (scale > 1f) newOffsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f
                                            scale = 1f; offsetX = 0f; offsetY = 0f
                                            currentPage = target.pageNumber
                                            if (!target.x.isNaN() && !target.y.isNaN()) {
                                                destinationHighlight = DestinationHighlight(target.pageNumber, target.x, target.y)
                                                delay(700)
                                                destinationHighlight = null
                                            }
                                        }
                                    }
                                },
                                groupIntoLines = ::groupIntoLines,
                                charsFrom = ::charsFrom
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageContent(
    page: PdfPage,
    index: Int,
    completedInkStrokes: Map<Int, List<InkStroke>>,
    penColor: StrokeColor,
    penThickness: StrokeThickness,
    searchState: SearchState,
    textSelection: PageTextSelection?,
    dragChars: List<TextChar>?,
    destinationHighlight: DestinationHighlight?,
    popupHeightPx: Int,
    density: androidx.compose.ui.unit.Density,
    currentSelectionRef: androidx.compose.runtime.State<PageTextSelection?>, // State used via .value in tap handler
    onBarsVisibleToggle: () -> Unit,
    onTextSelectionChanged: (PageTextSelection?) -> Unit,
    onDragCharsChanged: (List<TextChar>?) -> Unit,
    onIsDraggingHandleChanged: (Boolean) -> Unit,
    onPopupHeightPxChanged: (Int) -> Unit,
    onAddHighlight: (PageTextSelection) -> Unit,
    onDeleteHighlight: (PdfHighlight) -> Unit,
    onCopy: (String) -> Unit,
    onEraseInkStrokes: (Int, List<Int>) -> Unit,
    onAddInkAnnotation: (Int, List<Offset>, Int, Int) -> Unit,
    onLinkTap: (LinkTarget, android.graphics.RectF) -> Unit,
    groupIntoLines: (List<TextChar>) -> List<List<TextChar>>,
    charsFrom: (TextChar, TextChar, List<TextChar>) -> List<TextChar>
) {
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    var currentInkStroke by remember { mutableStateOf<List<Offset>?>(null) }
    val completedInkStrokesRef = rememberUpdatedState(completedInkStrokes)
    val indexRef = rememberUpdatedState(index)
    Box(modifier = Modifier.pointerInput(page.nativeWidth, page.nativeHeight) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture
            down.consume()
            val points = mutableListOf(down.position)
            currentInkStroke = (listOf(down.position))
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.find { it.id == down.id } ?: break
                if (!change.pressed) break
                change.consume()
                points.add(change.position)
                currentInkStroke = (points.toList())
            }
            if (pageSize != IntSize.Zero) {
                val normalizedPoints = points.map { Offset(it.x / pageSize.width, it.y / pageSize.height) }
                val currentIndex = indexRef.value
                val pageStrokes = completedInkStrokesRef.value[currentIndex]
                if (isScribble(points)) {
                    val intersecting = pageStrokes?.filter {
                        strokeIntersectsScribble(it.points, normalizedPoints) ||
                        strokeNearScribble(it.points, points, pageSize, 10f)
                    } ?: emptyList()
                    if (intersecting.isNotEmpty()) {
                        onEraseInkStrokes(currentIndex, intersecting.map { it.id })
                    }
                    currentInkStroke = null
                    return@awaitEachGesture
                }
                val stroke = if (points.size < 2) listOf(points[0], points[0]) else points
                onAddInkAnnotation(currentIndex, stroke, pageSize.width, pageSize.height)
            }
            currentInkStroke = (null)
        }
    }) {
        Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { pageSize = it }
                .pointerInput(page.links, page.words, page.highlights) {
                    detectTapGestures(
                        onDoubleTap = { onBarsVisibleToggle() },
                        onTap = { tapOffset ->
                            if (currentSelectionRef.value != null) {
                                onTextSelectionChanged(null)
                                return@detectTapGestures
                            }
                            if (pageSize == IntSize.Zero) return@detectTapGestures
                            val pdfX = tapOffset.x / pageSize.width * page.nativeWidth
                            val pdfY = tapOffset.y / pageSize.height * page.nativeHeight
                            val hit = page.links.firstOrNull { link ->
                                link.bounds.any { rect -> rect.contains(pdfX, pdfY) }
                            }
                            when (val target = hit?.target) {
                                is LinkTarget.Url -> onLinkTap(target, hit.bounds.first())
                                is LinkTarget.Goto -> onLinkTap(target, hit.bounds.first())
                                null -> {}
                            }
                        },
                        onLongPress = { longPressOffset ->
                            if (pageSize == IntSize.Zero) return@detectTapGestures
                            val prX = longPressOffset.x / pageSize.width * page.nativeWidth
                            val prY = longPressOffset.y / pageSize.height * page.nativeHeight
                            val hitHighlight = page.highlights.firstOrNull { h ->
                                h.bounds.any { rect ->
                                    rect.left <= prX && prX <= rect.right &&
                                    rect.top <= prY && prY <= rect.bottom + rect.height() * 0.3f
                                }
                            }
                            if (hitHighlight != null) {
                                val allChars = page.words.flatMap { it.chars }
                                val hlChars = allChars.filter { c ->
                                    hitHighlight.bounds.any { r ->
                                        val cVisualTop = c.bounds.top - c.bounds.height()
                                        val cVisualBot = c.bounds.top
                                        c.bounds.right > r.left && c.bounds.left < r.right &&
                                        cVisualBot > r.top && cVisualTop < r.bottom
                                    }
                                }
                                onTextSelectionChanged(PageTextSelection(index, hlChars, hitHighlight))
                                return@detectTapGestures
                            }
                            val hitWord = page.words.firstOrNull { w ->
                                val visualTop = w.bounds.top - w.bounds.height()
                                prX >= w.bounds.left && prX <= w.bounds.right &&
                                prY >= visualTop && prY <= w.bounds.bottom
                            }
                            if (hitWord != null) {
                                onTextSelectionChanged(PageTextSelection(index, hitWord.chars))
                            }
                        }
                    )
                }
        )
        if (page.highlights.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                page.highlights.forEach { h ->
                    h.bounds.forEach { r ->
                        val l = r.left   / page.nativeWidth  * size.width
                        val t = r.top    / page.nativeHeight * size.height
                        val rr = r.right  / page.nativeWidth  * size.width
                        val b = r.bottom / page.nativeHeight * size.height
                        drawRect(Color(0xFFFFF176), Offset(l, t), Size(rr - l, b - t), blendMode = BlendMode.Multiply)
                    }
                }
            }
        }
        val highlight = destinationHighlight
        if (highlight != null && highlight.pageIndex == index && pageSize != IntSize.Zero) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val cx = highlight.x / page.nativeWidth * size.width
                val cy = highlight.y / page.nativeHeight * size.height
                drawCircle(
                    color = Color(0xFFFF9999),
                    radius = 24.dp.toPx(),
                    center = Offset(cx, cy),
                    blendMode = BlendMode.Multiply
                )
            }
        }
        val pageSearchMatches = searchState.matches.filter { it.pageIndex == index }
        val currentMatch = searchState.matches.getOrNull(searchState.currentIndex)
        if (pageSearchMatches.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                pageSearchMatches.forEach { match ->
                    val isCurrent = (match === currentMatch)
                    val color = if (isCurrent) Color(0xFF00C853) else Color(0xFFB9F6CA)
                    match.wordBounds.forEach { r ->
                        val l  = r.left                / page.nativeWidth  * size.width
                        val t  = (r.top - r.height())  / page.nativeHeight * size.height
                        val rr = r.right               / page.nativeWidth  * size.width
                        val b  = r.top                 / page.nativeHeight * size.height
                        drawRect(color, Offset(l, t), Size(rr - l, b - t), blendMode = BlendMode.Multiply)
                    }
                }
            }
        }
        val pageStrokes = completedInkStrokes[index]
        if (!pageStrokes.isNullOrEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val baseStrokePx = size.width / page.nativeWidth
                for (stroke in pageStrokes) {
                    val pts = stroke.points
                    if (pts.size < 2) continue
                    val c = stroke.color.composeColor
                    val w = baseStrokePx * stroke.thickness.multiplier
                    val x0 = pts.first().x * size.width
                    val y0 = pts.first().y * size.height
                    if (pts.first() == pts.last()) {
                        drawCircle(c, radius = w / 2f, center = Offset(x0, y0))
                    } else {
                        val path = Path().apply {
                            moveTo(x0, y0)
                            pts.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                        }
                        val cap = if (stroke.roundCap) StrokeCap.Round else StrokeCap.Butt
                        val join = if (stroke.roundCap) StrokeJoin.Round else StrokeJoin.Miter
                        drawPath(path, color = c, style = Stroke(width = w, cap = cap, join = join))
                    }
                }
            }
        }
        val inkStroke = currentInkStroke
        if (inkStroke != null && inkStroke.size >= 2) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val baseStrokePx = size.width / page.nativeWidth
                val c = penColor.composeColor
                val w = baseStrokePx * penThickness.multiplier
                if (inkStroke.first() == inkStroke.last()) {
                    drawCircle(c, radius = w / 2f, center = inkStroke.first())
                } else {
                    val path = Path().apply {
                        moveTo(inkStroke.first().x, inkStroke.first().y)
                        inkStroke.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, color = c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        val sel = textSelection
        if (sel != null && sel.pageIndex == index) {
            val displayedChars = dragChars ?: sel.selectedChars
            val overlayChars = if (sel.existingHighlight != null) sel.selectedChars else displayedChars
            Canvas(modifier = Modifier.matchParentSize()) {
                groupIntoLines(overlayChars).forEach { lineChars ->
                    val l = lineChars.minOf { it.bounds.left }                     / page.nativeWidth  * size.width
                    val t = lineChars.minOf { it.bounds.top - it.bounds.height() * 1.6f } / page.nativeHeight * size.height
                    val r = lineChars.maxOf { it.bounds.right }                        / page.nativeWidth  * size.width
                    val b = lineChars.maxOf { it.bounds.top + it.bounds.height() * 0.5f } / page.nativeHeight * size.height
                    drawRect(Color(0xFFBBDEFB), Offset(l, t), Size(r - l, b - t), blendMode = BlendMode.Multiply)
                }
                if (sel.existingHighlight == null) {
                    val firstChar = displayedChars.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left }))
                    val lastChar  = displayedChars.maxWithOrNull(compareBy({ it.bounds.bottom }, { it.bounds.right }))
                    val handleColor = Color(0xFF1565C0.toInt())
                    val handleR = 10.dp.toPx()
                    fun drawTeardrop(cx: Float, cy: Float) {
                        val bodyY = cy + handleR * 1.5f
                        val path = Path()
                        path.moveTo(cx, cy)
                        path.cubicTo(cx + handleR * 0.4f, cy + handleR * 0.3f,
                                     cx + handleR,         bodyY - handleR * 0.5f,
                                     cx + handleR,         bodyY)
                        path.arcTo(Rect(cx - handleR, bodyY - handleR, cx + handleR, bodyY + handleR),
                            startAngleDegrees = 0f, sweepAngleDegrees = 180f, forceMoveTo = false)
                        path.cubicTo(cx - handleR,         bodyY - handleR * 0.5f,
                                     cx - handleR * 0.4f, cy + handleR * 0.3f,
                                     cx,                   cy)
                        path.close()
                        drawPath(path, handleColor)
                    }
                    firstChar?.let {
                        drawTeardrop(
                            it.bounds.left   / page.nativeWidth  * size.width,
                            it.bounds.bottom / page.nativeHeight * size.height)
                    }
                    lastChar?.let {
                        drawTeardrop(
                            it.bounds.right  / page.nativeWidth  * size.width,
                            it.bounds.bottom / page.nativeHeight * size.height)
                    }
                }
            }
            Box(modifier = Modifier.matchParentSize().pointerInput(sel) {
                val touchThreshPx = 32.dp.toPx()
                val handleOffsetY = 10.dp.toPx() * 1.5f  // body centre offset below tip
                awaitEachGesture {
                    val currentChars = dragChars ?: sel.selectedChars
                    val firstChar = currentChars.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left })) ?: return@awaitEachGesture
                    val lastChar  = currentChars.maxWithOrNull(compareBy({ it.bounds.bottom }, { it.bounds.right })) ?: return@awaitEachGesture
                    val startX = firstChar.bounds.left   / page.nativeWidth  * pageSize.width.toFloat()
                    val startY = firstChar.bounds.bottom / page.nativeHeight * pageSize.height.toFloat() + handleOffsetY
                    val endX   = lastChar.bounds.right   / page.nativeWidth  * pageSize.width.toFloat()
                    val endY   = lastChar.bounds.bottom  / page.nativeHeight * pageSize.height.toFloat() + handleOffsetY
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downPos = down.position
                    val distStart = (downPos - Offset(startX, startY)).getDistance()
                    val distEnd   = (downPos - Offset(endX, endY)).getDistance()
                    val hitStart  = distStart < touchThreshPx
                    val hitEnd    = distEnd   < touchThreshPx
                    if (!hitStart && !hitEnd) {
                        onTextSelectionChanged(null)
                        return@awaitEachGesture
                    }
                    val draggingStart = hitStart && (!hitEnd || distStart <= distEnd)
                    // Anchor: map the actual touch-down position to the grabbed character's PDF-space
                    // centre. This way dragging from any point on the handle (tip, body, bottom)
                    // tracks relative to where the finger landed, never jumping to the wrong row.
                    val dragAnchorScreenY = downPos.y
                    val dragAnchorPdfY = if (draggingStart)
                        (firstChar.bounds.top + firstChar.bounds.bottom) / 2f
                    else
                        (lastChar.bounds.top  + lastChar.bounds.bottom)  / 2f
                    down.consume()
                    var lastDragChars: List<TextChar>? = null
                    onIsDraggingHandleChanged(true)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            val pos = event.changes.firstOrNull()?.position ?: continue
                            val prX = pos.x / pageSize.width.toFloat() * page.nativeWidth
                            val prY = (pos.y - dragAnchorScreenY) / pageSize.height.toFloat() * page.nativeHeight + dragAnchorPdfY
                            val allChars = page.words.flatMap { it.chars }
                            val nearest = allChars.minByOrNull { c ->
                                val cx = (c.bounds.left + c.bounds.right) / 2f
                                val cy = (c.bounds.top  + c.bounds.bottom) / 2f
                                (cx - prX) * (cx - prX) + (cy - prY) * (cy - prY)
                            } ?: continue
                            val lines = groupIntoLines(allChars)
                            val nearestCX = nearest.bounds.centerX()
                            val newDragChars = if (draggingStart) {
                                val endChar = sel.selectedChars.last()
                                val endLineIdx = lines.indexOfFirst { line -> endChar in line }
                                val nearestLineIdx = lines.indexOfFirst { line -> nearest in line }
                                val clampedNearest = if (endLineIdx >= 0 && nearestLineIdx > endLineIdx)
                                    lines[endLineIdx].minByOrNull { kotlin.math.abs(it.bounds.centerX() - nearestCX) } ?: nearest
                                else nearest
                                charsFrom(clampedNearest, endChar, allChars)
                            } else {
                                val startChar = sel.selectedChars.first()
                                val startLineIdx = lines.indexOfFirst { line -> startChar in line }
                                val nearestLineIdx = lines.indexOfFirst { line -> nearest in line }
                                val clampedNearest = if (startLineIdx >= 0 && nearestLineIdx < startLineIdx)
                                    lines[startLineIdx].minByOrNull { kotlin.math.abs(it.bounds.centerX() - nearestCX) } ?: nearest
                                else nearest
                                charsFrom(startChar, clampedNearest, allChars)
                            }
                            lastDragChars = newDragChars
                            onDragCharsChanged(newDragChars)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        onIsDraggingHandleChanged(false)
                    }
                    val committed = lastDragChars
                    if (committed != null) {
                        onTextSelectionChanged(sel.copy(selectedChars = committed))
                        onDragCharsChanged(null)
                    }
                }
            })
            if (displayedChars.isNotEmpty()) {
                val selTopPR    = displayedChars.minOf { it.bounds.top - it.bounds.height() }
                val selCenterX  = (displayedChars.minOf { it.bounds.left } + displayedChars.maxOf { it.bounds.right }) / 2f
                val popupLocalX = (selCenterX / page.nativeWidth  * pageSize.width.toFloat()).roundToInt()
                val popupLocalY = (selTopPR   / page.nativeHeight * pageSize.height.toFloat()).roundToInt()
                with(density) {
                    val gapPx = 8.dp.roundToPx()
                    Box(modifier = Modifier
                        .offset {
                            IntOffset(
                                popupLocalX - 60.dp.roundToPx(),
                                popupLocalY - gapPx - popupHeightPx
                            )
                        }
                        .onSizeChanged { onPopupHeightPxChanged(it.height) }
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)) {
                                if (sel.existingHighlight == null) {
                                    IconButton(onClick = {
                                        onAddHighlight(sel)
                                        onTextSelectionChanged(null)
                                    }) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(Color(0xFFFFEB3B), shape = RoundedCornerShape(4.dp))
                                        ) {
                                            Text("A", style = MaterialTheme.typography.titleMedium)
                                        }
                                    }
                                } else {
                                    IconButton(onClick = {
                                        onDeleteHighlight(sel.existingHighlight)
                                        onTextSelectionChanged(null)
                                    }) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(Color(0xFFFFEB3B), shape = RoundedCornerShape(4.dp))
                                        ) {
                                            Text("A", style = MaterialTheme.typography.titleMedium,
                                                color = Color(0xFF9E9E9E))
                                        }
                                    }
                                }
                                IconButton(onClick = {
                                    val selectedSet = sel.selectedChars.toHashSet()
                                    val text = page.words
                                        .filter { w -> w.chars.any { it in selectedSet } }
                                        .joinToString(" ") { w ->
                                            w.chars.filter { it in selectedSet }.joinToString("") { it.text }
                                        }
                                    onCopy(text)
                                    onTextSelectionChanged(null)
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PenToolbar(
    selectedColor: StrokeColor, selectedThickness: StrokeThickness,
    onColorSelected: (StrokeColor) -> Unit, onThicknessSelected: (StrokeThickness) -> Unit
) {
    Surface(tonalElevation = 0.dp, shadowElevation = 4.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StrokeThickness.entries.forEach { t ->
                    ThicknessButton(t, t == selectedThickness) { onThicknessSelected(t) }
                }
            }
            Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StrokeColor.entries.forEach { c ->
                    ColorButton(c, c == selectedColor) { onColorSelected(c) }
                }
            }
        }
    }
}

@Composable
private fun ThicknessButton(thickness: StrokeThickness, isSelected: Boolean, onClick: () -> Unit) {
    val lineThickness = when (thickness) {
        StrokeThickness.THIN -> 1.5.dp
        StrokeThickness.MEDIUM -> 3.dp
        StrokeThickness.THICK -> 6.dp
    }
    val lineWidth = 20.dp
    val pillShape = RoundedCornerShape(50)
    val roundedShape = RoundedCornerShape(35)
    Box(
        modifier = Modifier.size(32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(Modifier.size(26.dp).border(1.5.dp,  MaterialTheme.colorScheme.outline, roundedShape))
        }
        Box(Modifier.width(lineWidth).height(lineThickness).clip(pillShape).background(Color.Black))
    }
}

@Composable
private fun ColorButton(color: StrokeColor, isSelected: Boolean, onClick: () -> Unit) {
    val ringColor = MaterialTheme.colorScheme.outline
    val roundedShape = RoundedCornerShape(35)
    Box(
        modifier = Modifier.size(32.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Box(Modifier.size(26.dp).border(1.5.dp, ringColor, roundedShape))
        }
        Box(
            Modifier.size(18.dp).clip(roundedShape).background(color.composeColor)
        )
    }
}

private fun isScribble(points: List<Offset>): Boolean {
    if (points.size < 3) return false
    var totalLength = 0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i-1].x
        val dy = points[i].y - points[i-1].y
        totalLength += sqrt(dx * dx + dy * dy)
    }
    if (totalLength < 20f) return false
    var reversals = 0
    for (i in 1 until points.size - 1) {
        val dx1 = points[i].x - points[i-1].x; val dy1 = points[i].y - points[i-1].y
        val dx2 = points[i+1].x - points[i].x; val dy2 = points[i+1].y - points[i].y
        val dot = dx1 * dx2 + dy1 * dy2
        if (dot < 0f && dot * dot > 0.0302f * (dx1*dx1 + dy1*dy1) * (dx2*dx2 + dy2*dy2)) reversals++
    }
    return reversals >= 3
}

private fun segmentsIntersect(a1: Offset, a2: Offset, b1: Offset, b2: Offset): Boolean {
    fun cross(o: Offset, a: Offset, b: Offset) =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
    val d1 = cross(b1, b2, a1); val d2 = cross(b1, b2, a2)
    val d3 = cross(a1, a2, b1); val d4 = cross(a1, a2, b2)
    return (d1 > 0 && d2 < 0 || d1 < 0 && d2 > 0) &&
           (d3 > 0 && d4 < 0 || d3 < 0 && d4 > 0)
}

private fun strokeIntersectsScribble(strokePts: List<Offset>, scribbleNorm: List<Offset>): Boolean {
    for (i in 0 until strokePts.size - 1)
        for (j in 0 until scribbleNorm.size - 1)
            if (segmentsIntersect(strokePts[i], strokePts[i+1], scribbleNorm[j], scribbleNorm[j+1])) return true
    return false
}

private fun pointToSegmentDist(p: Offset, a: Offset, b: Offset): Float {
    val dx = b.x - a.x; val dy = b.y - a.y
    if (dx == 0f && dy == 0f) {
        val ex = p.x - a.x; val ey = p.y - a.y
        return sqrt(ex * ex + ey * ey)
    }
    val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)
    val cx = a.x + t.coerceIn(0f, 1f) * dx
    val cy = a.y + t.coerceIn(0f, 1f) * dy
    val ex = p.x - cx; val ey = p.y - cy
    return sqrt(ex * ex + ey * ey)
}

private fun strokeNearScribble(
    strokeNorm: List<Offset>, scribblePx: List<Offset>, pageSize: IntSize, thresholdPx: Float
): Boolean {
    val strokePx = strokeNorm.map { Offset(it.x * pageSize.width, it.y * pageSize.height) }
    // Check stroke points near scribble segments
    for (pt in strokePx)
        for (j in 0 until scribblePx.size - 1)
            if (pointToSegmentDist(pt, scribblePx[j], scribblePx[j + 1]) <= thresholdPx) return true
    // Check scribble points near stroke segments (catches parallel/collinear cases)
    for (pt in scribblePx)
        for (j in 0 until strokePx.size - 1)
            if (pointToSegmentDist(pt, strokePx[j], strokePx[j + 1]) <= thresholdPx) return true
    return false
}
