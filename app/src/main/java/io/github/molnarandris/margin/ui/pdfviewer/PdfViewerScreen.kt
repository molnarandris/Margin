package io.github.molnarandris.margin.ui.pdfviewer

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Path
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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

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
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val pdfTitle  by viewModel.displayTitle.collectAsState()
    val pdfAuthor by viewModel.displayAuthor.collectAsState()
    var isEditDialogVisible by remember { mutableStateOf(false) }
    var titleEditText  by remember { mutableStateOf("") }
    var authorEditText by remember { mutableStateOf("") }

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
        topBar = {
            TopAppBar(
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
                            if (pdfAuthor.isNotEmpty()) {
                                Text(
                                    text = pdfAuthor,
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
        }
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
                data class DestinationHighlight(val pageIndex: Int, val x: Float, val y: Float)
                data class JumpOrigin(val pageIndex: Int, val scrollOffset: Int, val highlightX: Float, val highlightY: Float)
                data class TextSelection(
                    val pageIndex: Int,
                    val selectedChars: List<TextChar>,
                    val existingHighlight: PdfHighlight? = null
                )

                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var destinationHighlight by remember { mutableStateOf<DestinationHighlight?>(null) }
                var jumpOrigin by remember { mutableStateOf<JumpOrigin?>(null) }
                var textSelection by remember { mutableStateOf<TextSelection?>(null) }
                // Used during handle dragging so we don't update textSelection on every event
                var dragChars by remember { mutableStateOf<List<TextChar>?>(null) }
                var isDraggingHandle by remember { mutableStateOf(false) }
                var popupHeightPx by remember { mutableStateOf(0) }
                val currentSelectionRef = rememberUpdatedState(textSelection)
                val lazyListState = rememberLazyListState()
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
                    coroutineScope.launch {
                        lazyListState.scrollToItem(origin.pageIndex, origin.scrollOffset)
                        destinationHighlight = DestinationHighlight(origin.pageIndex, origin.highlightX, origin.highlightY)
                        delay(500)
                        destinationHighlight = null
                    }
                }

                LaunchedEffect(Unit) {
                    snapshotFlow { scale to lazyListState.firstVisibleItemIndex }
                        .debounce(300)
                        .collect { (currentScale, _) ->
                            val visibleIndices = lazyListState.layoutInfo.visibleItemsInfo.map { it.index }
                            viewModel.updateRenderScale(currentScale, visibleIndices)
                        }
                }

                // Track visible page for phase-2 highlight prioritization
                LaunchedEffect(Unit) {
                    snapshotFlow { lazyListState.firstVisibleItemIndex }
                        .collect { viewModel.onVisiblePageChanged(it) }
                }

                // Dismiss selection on scroll
                LaunchedEffect(Unit) {
                    snapshotFlow { lazyListState.firstVisibleItemScrollOffset }
                        .drop(1)
                        .collect { textSelection = null }
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
                    val displayedPageHeight = displayedPageWidth * page.nativeHeight / page.nativeWidth

                    val matchCenterX = (match.wordBounds.minOf { it.left } + match.wordBounds.maxOf { it.right }) / 2f
                    val matchCenterY = (match.wordBounds.minOf { it.top - it.height() } + match.wordBounds.maxOf { it.top }) / 2f

                    val destXPx = matchCenterX / page.nativeWidth * displayedPageWidth
                    val newOffsetX = screenWidthPx * scale / 2f - marginPx - destXPx
                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                    offsetX = if (scale > 1f) newOffsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f

                    val destYPx = matchCenterY / page.nativeHeight * displayedPageHeight
                    val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                    val scrollOffset = (destYPx - viewportHeight / 2f).coerceAtLeast(0f).roundToInt()
                    lazyListState.animateScrollToItem(match.pageIndex, scrollOffset)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color(0xFFE0E0E0))
                        .pointerInput(Unit) {
                            detectTapGestures { textSelection = null }
                        }
                        .pointerInput(screenWidthPx, marginPx) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var wasMultiTouch = false
                                var everMultiTouch = false
                                var singleTouchAxis = 0  // 0 = undecided, 1 = horizontal, -1 = vertical
                                var accumDx = 0f
                                var accumDy = 0f
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
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

                                                val newScale = (scale * zoomChange).coerceIn(0.5f, 5f)
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

                                                val anchorY = lazyListState.layoutInfo
                                                    .visibleItemsInfo.firstOrNull()?.offset?.toFloat() ?: 0f
                                                val scrollDelta = (centroid.y - anchorY) * (actualZoom - 1f) - panChange.y
                                                lazyListState.dispatchRawDelta(scrollDelta)
                                            } else {
                                                offsetX = if (scale > 1f) {
                                                    val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                    (offsetX + panChange.x).coerceIn(-maxOffsetX, maxOffsetX)
                                                } else 0f
                                                lazyListState.dispatchRawDelta(-panChange.y)
                                            }
                                        }
                                        wasMultiTouch = true
                                        everMultiTouch = true
                                        singleTouchAxis = 0
                                        event.changes.forEach { it.consume() }
                                    } else if (!everMultiTouch && !isDraggingHandle) {
                                        // Single-finger: detect axis then handle horizontal scroll (not for stylus)
                                        val change = event.changes.firstOrNull { it.type != PointerType.Stylus }
                                        if (change != null) {
                                            val dx = change.position.x - change.previousPosition.x
                                            val dy = change.position.y - change.previousPosition.y
                                            if (singleTouchAxis == 0) {
                                                accumDx += dx
                                                accumDy += dy
                                                val distSq = accumDx * accumDx + accumDy * accumDy
                                                if (distSq > 64f) {  // 8px threshold
                                                    singleTouchAxis = if (kotlin.math.abs(accumDx) > kotlin.math.abs(accumDy)) 1 else -1
                                                }
                                            }
                                            if (singleTouchAxis == 1 && scale > 1f) {
                                                val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                offsetX = (offsetX + dx).coerceIn(-maxOffsetX, maxOffsetX)
                                                change.consume()
                                            }
                                        }
                                    } else {
                                        wasMultiTouch = false
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    LazyColumn(
                        state = lazyListState,
                        modifier = Modifier
                            .requiredWidth(contentWidthDp)
                            .fillMaxHeight()
                            .align(Alignment.TopCenter)
                            .offset { IntOffset(offsetX.roundToInt(), 0) },
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        itemsIndexed(state.pages) { index, page ->
                            var pageSize by remember { mutableStateOf(IntSize.Zero) }
                            var currentInkStroke by remember { mutableStateOf<List<Offset>?>(null) }
                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                shape = RectangleShape,
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Box(modifier = Modifier.pointerInput(page.nativeWidth, page.nativeHeight) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (down.type != PointerType.Stylus) return@awaitEachGesture
                                        down.consume()
                                        val points = mutableListOf(down.position)
                                        currentInkStroke = listOf(down.position)
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.find { it.id == down.id } ?: break
                                            if (!change.pressed) break
                                            change.consume()
                                            points.add(change.position)
                                            currentInkStroke = points.toList()
                                        }
                                        if (pageSize != IntSize.Zero) {
                                            // A single tap produces one point; duplicate it so addInkAnnotation
                                            // treats it as a zero-length stroke (rendered as a dot).
                                            val stroke = if (points.size < 2) listOf(points[0], points[0]) else points
                                            viewModel.addInkAnnotation(index, stroke, pageSize.width, pageSize.height)
                                        }
                                        currentInkStroke = null
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
                                                    onTap = { tapOffset ->
                                                        // Dismiss selection on tap
                                                        if (currentSelectionRef.value != null) {
                                                            textSelection = null
                                                            return@detectTapGestures
                                                        }
                                                        if (pageSize == IntSize.Zero) return@detectTapGestures
                                                        val pdfX = tapOffset.x / pageSize.width * page.nativeWidth
                                                        val pdfY = tapOffset.y / pageSize.height * page.nativeHeight
                                                        val hit = page.links.firstOrNull { link ->
                                                            link.bounds.any { rect -> rect.contains(pdfX, pdfY) }
                                                        }
                                                        when (val target = hit?.target) {
                                                            is LinkTarget.Url -> context.startActivity(
                                                                Intent(Intent.ACTION_VIEW, target.uri)
                                                            )
                                                            is LinkTarget.Goto -> coroutineScope.launch {
                                                                val rect = hit.bounds.first()
                                                                jumpOrigin = JumpOrigin(
                                                                    pageIndex = lazyListState.firstVisibleItemIndex,
                                                                    scrollOffset = lazyListState.firstVisibleItemScrollOffset,
                                                                    highlightX = rect.centerX(),
                                                                    highlightY = rect.centerY()
                                                                )

                                                                val displayedPageWidth = screenWidthPx * scale - 2 * marginPx
                                                                val displayedPageHeight = displayedPageWidth * page.nativeHeight / page.nativeWidth

                                                                val destXPx = target.x / page.nativeWidth * displayedPageWidth
                                                                val newOffsetX = screenWidthPx * scale / 2f - marginPx - destXPx
                                                                val maxOffsetX = marginPx + screenWidthPx * (scale - 1f) / 2f
                                                                offsetX = if (scale > 1f) newOffsetX.coerceIn(-maxOffsetX, maxOffsetX) else 0f

                                                                val destYPx = target.y / page.nativeHeight * displayedPageHeight
                                                                val viewportHeight = lazyListState.layoutInfo.viewportSize.height
                                                                val scrollOffset = (destYPx - viewportHeight / 2f).coerceAtLeast(0f).roundToInt()
                                                                lazyListState.animateScrollToItem(target.pageNumber, scrollOffset)

                                                                if (!target.x.isNaN() && !target.y.isNaN()) {
                                                                    destinationHighlight = DestinationHighlight(target.pageNumber, target.x, target.y)
                                                                    delay(500)
                                                                    destinationHighlight = null
                                                                }
                                                            }
                                                            null -> {}
                                                        }
                                                    },
                                                    onLongPress = { longPressOffset ->
                                                        if (pageSize == IntSize.Zero) return@detectTapGestures
                                                        val prX = longPressOffset.x / pageSize.width * page.nativeWidth
                                                        val prY = longPressOffset.y / pageSize.height * page.nativeHeight

                                                        // 1. Check existing highlights first
                                                        // PdfHighlight.bounds: top=visualTop, bottom=baseline (standard Y-down).
                                                        // Extend bottom by 30% of height to cover descenders.
                                                        val hitHighlight = page.highlights.firstOrNull { h ->
                                                            h.bounds.any { rect ->
                                                                rect.left <= prX && prX <= rect.right &&
                                                                rect.top <= prY && prY <= rect.bottom + rect.height() * 0.3f
                                                            }
                                                        }
                                                        if (hitHighlight != null) {
                                                            // TextChar.bounds uses non-standard coords: top=baseline, bottom=baseline+capHeight.
                                                            // Visual region of char: [top-height(), top]. Match against highlight's [r.top, r.bottom].
                                                            val allChars = page.words.flatMap { it.chars }
                                                            val hlChars = allChars.filter { c ->
                                                                hitHighlight.bounds.any { r ->
                                                                    val cVisualTop = c.bounds.top - c.bounds.height()
                                                                    val cVisualBot = c.bounds.top
                                                                    c.bounds.right > r.left && c.bounds.left < r.right &&
                                                                    cVisualBot > r.top && cVisualTop < r.bottom
                                                                }
                                                            }
                                                            textSelection = TextSelection(index, hlChars, hitHighlight)
                                                            return@detectTapGestures
                                                        }

                                                        // 2. Check text words
                                                        val hitWord = page.words.firstOrNull { w ->
                                                            val visualTop = w.bounds.top - w.bounds.height()
                                                            prX >= w.bounds.left && prX <= w.bounds.right &&
                                                            prY >= visualTop && prY <= w.bounds.bottom
                                                        }
                                                        if (hitWord != null) {
                                                            textSelection = TextSelection(index, hitWord.chars)
                                                        }
                                                        // 3. No text → do nothing
                                                    }
                                                )
                                            }
                                    )
                                    // Yellow highlights — rendered in page-local space so they zoom/scroll with the page
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

                                    // Search match highlights
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

                                    // Completed ink strokes (normalized coords → screen coords)
                                    val pageStrokes = completedInkStrokes[index]
                                    if (!pageStrokes.isNullOrEmpty()) {
                                        Canvas(modifier = Modifier.matchParentSize()) {
                                            val strokePx = size.width / page.nativeWidth
                                            for (stroke in pageStrokes) {
                                                if (stroke.size < 2) continue
                                                val x0 = stroke.first().x * size.width
                                                val y0 = stroke.first().y * size.height
                                                if (stroke.first() == stroke.last()) {
                                                    drawCircle(Color.Black, radius = strokePx / 2f, center = Offset(x0, y0))
                                                } else {
                                                    val path = Path().apply {
                                                        moveTo(x0, y0)
                                                        stroke.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                                                    }
                                                    drawPath(path, color = Color.Black, style = Stroke(width = strokePx))
                                                }
                                            }
                                        }
                                    }

                                    // In-progress ink stroke overlay
                                    val inkStroke = currentInkStroke
                                    if (inkStroke != null && inkStroke.size >= 2) {
                                        Canvas(modifier = Modifier.matchParentSize()) {
                                            val strokePx = size.width / page.nativeWidth
                                            if (inkStroke.first() == inkStroke.last()) {
                                                drawCircle(Color.Black, radius = strokePx / 2f, center = inkStroke.first())
                                            } else {
                                                val path = Path().apply {
                                                    moveTo(inkStroke.first().x, inkStroke.first().y)
                                                    inkStroke.drop(1).forEach { lineTo(it.x, it.y) }
                                                }
                                                drawPath(path, color = Color.Black, style = Stroke(width = strokePx))
                                            }
                                        }
                                    }

                                    // Blue selection overlay — rendered in page-local space so it zooms/scrolls with the page
                                    val sel = textSelection
                                    if (sel != null && sel.pageIndex == index) {
                                        val displayedChars = dragChars ?: sel.selectedChars
                                        val overlayChars = if (sel.existingHighlight != null) sel.selectedChars else displayedChars

                                        Canvas(modifier = Modifier.matchParentSize()) {
                                            groupIntoLines(overlayChars).forEach { lineChars ->
                                                val l = lineChars.minOf { it.bounds.left }                     / page.nativeWidth  * size.width
                                                val t = lineChars.minOf { it.bounds.top - it.bounds.height() } / page.nativeHeight * size.height
                                                val r = lineChars.maxOf { it.bounds.right }                    / page.nativeWidth  * size.width
                                                val b = lineChars.maxOf { it.bounds.top }                      / page.nativeHeight * size.height
                                                drawRect(Color(0xFFBBDEFB), Offset(l, t), Size(r - l, b - t), blendMode = BlendMode.Multiply)
                                            }
                                            if (sel.existingHighlight == null) {
                                                val firstChar = displayedChars.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left }))
                                                val lastChar  = displayedChars.maxWithOrNull(compareBy({ it.bounds.bottom }, { it.bounds.right }))
                                                firstChar?.let {
                                                    drawCircle(Color(0xFF1565C0.toInt()), radius = 10.dp.toPx(),
                                                        center = Offset(it.bounds.left  / page.nativeWidth  * size.width,
                                                                        it.bounds.top / page.nativeHeight * size.height))
                                                }
                                                lastChar?.let {
                                                    drawCircle(Color(0xFF1565C0.toInt()), radius = 10.dp.toPx(),
                                                        center = Offset(it.bounds.right  / page.nativeWidth  * size.width,
                                                                        it.bounds.top / page.nativeHeight * size.height))
                                                }
                                            }
                                        }

                                        Box(modifier = Modifier.matchParentSize().pointerInput(sel) {
                                            val touchThreshPx = 32.dp.toPx()
                                            awaitEachGesture {
                                                val currentChars = dragChars ?: sel.selectedChars
                                                val firstChar = currentChars.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left })) ?: return@awaitEachGesture
                                                val lastChar  = currentChars.maxWithOrNull(compareBy({ it.bounds.bottom }, { it.bounds.right })) ?: return@awaitEachGesture

                                                val startX = firstChar.bounds.left  / page.nativeWidth  * pageSize.width.toFloat()
                                                val startY = firstChar.bounds.top   / page.nativeHeight * pageSize.height.toFloat()
                                                val endX   = lastChar.bounds.right  / page.nativeWidth  * pageSize.width.toFloat()
                                                val endY   = lastChar.bounds.top    / page.nativeHeight * pageSize.height.toFloat()

                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val downPos = down.position
                                                val hitStart = (downPos - Offset(startX, startY)).getDistance() < touchThreshPx
                                                val hitEnd   = (downPos - Offset(endX, endY)).getDistance()   < touchThreshPx

                                                if (!hitStart && !hitEnd) {
                                                    textSelection = null
                                                    return@awaitEachGesture
                                                }
                                                down.consume()
                                                isDraggingHandle = true
                                                try {
                                                    do {
                                                        val event = awaitPointerEvent()
                                                        event.changes.forEach { it.consume() }
                                                        val pos = event.changes.firstOrNull()?.position ?: continue
                                                        val prX = pos.x / pageSize.width.toFloat()  * page.nativeWidth
                                                        val prY = pos.y / pageSize.height.toFloat() * page.nativeHeight
                                                        val allChars = page.words.flatMap { it.chars }
                                                        val nearest = allChars.minByOrNull { c ->
                                                            val cx = (c.bounds.left + c.bounds.right) / 2f
                                                            val cy = (c.bounds.top  + c.bounds.bottom) / 2f
                                                            (cx - prX) * (cx - prX) + (cy - prY) * (cy - prY)
                                                        } ?: continue
                                                        val lines = groupIntoLines(allChars)
                                                        val nearestCX = nearest.bounds.centerX()
                                                        dragChars = if (hitStart) {
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
                                                    } while (event.changes.any { it.pressed })
                                                } finally {
                                                    isDraggingHandle = false
                                                }

                                                val committed = dragChars
                                                if (committed != null) {
                                                    textSelection = sel.copy(selectedChars = committed)
                                                    dragChars = null
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
                                                    .onSizeChanged { popupHeightPx = it.height }
                                                ) {
                                                    Card(elevation = CardDefaults.cardElevation(4.dp)) {
                                                        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                                            if (sel.existingHighlight == null) {
                                                                TextButton(onClick = {
                                                                    viewModel.addHighlight(sel.pageIndex, sel.selectedChars)
                                                                    textSelection = null
                                                                }) { Text("Highlight") }
                                                            } else {
                                                                TextButton(onClick = {
                                                                    viewModel.deleteHighlight(sel.existingHighlight)
                                                                    textSelection = null
                                                                }) { Text("Delete") }
                                                            }
                                                            TextButton(onClick = {
                                                                val selectedSet = sel.selectedChars.toHashSet()
                                                                val text = page.words
                                                                    .filter { w -> w.chars.any { it in selectedSet } }
                                                                    .joinToString(" ") { w ->
                                                                        w.chars.filter { it in selectedSet }.joinToString("") { it.text }
                                                                    }
                                                                clipboardManager.setText(AnnotatedString(text))
                                                                textSelection = null
                                                            }) { Text("Copy") }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
