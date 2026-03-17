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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    var isSearchVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(searchQuery) {
        viewModel.search(searchQuery)
    }

    LaunchedEffect(isSearchVisible) {
        if (isSearchVisible) {
            searchFocusRequester.requestFocus()
            keyboardController?.show()
        }
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
                        Text("PDF Viewer")
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
                    val selectedWords: List<TextWord>,
                    val existingHighlight: PdfHighlight? = null
                )

                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                var destinationHighlight by remember { mutableStateOf<DestinationHighlight?>(null) }
                var jumpOrigin by remember { mutableStateOf<JumpOrigin?>(null) }
                var textSelection by remember { mutableStateOf<TextSelection?>(null) }
                // Used during handle dragging so we don't update textSelection on every event
                var dragWords by remember { mutableStateOf<List<TextWord>?>(null) }
                var popupHeightPx by remember { mutableStateOf(0) }
                val currentSelectionRef = rememberUpdatedState(textSelection)
                val lazyListState = rememberLazyListState()
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current

                // Helper: group words into lines by proximity of their top coordinates.
                fun groupIntoLines(words: List<TextWord>): List<List<TextWord>> {
                    if (words.isEmpty()) return emptyList()
                    val threshold = words.map { it.bounds.height() }.average().toFloat() * 0.5f
                    val lines = mutableListOf<MutableList<TextWord>>()
                    for (word in words.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
                        val line = lines.firstOrNull { kotlin.math.abs(it.first().bounds.top - word.bounds.top) < threshold }
                        if (line != null) line.add(word) else lines.add(mutableListOf(word))
                    }
                    return lines
                }

                // Helper: words in PDFBox reading order between start and end (inclusive).
                // allWords retains the extraction order, which handles multi-column correctly.
                fun wordsFrom(start: TextWord, end: TextWord, allWords: List<TextWord>): List<TextWord> {
                    val startIdx = allWords.indexOf(start).takeIf { it >= 0 } ?: return listOf(start)
                    val endIdx = allWords.indexOf(end).takeIf { it >= 0 } ?: return listOf(end)
                    val lo = minOf(startIdx, endIdx)
                    val hi = maxOf(startIdx, endIdx)
                    return allWords.subList(lo, hi + 1)
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
                                        event.changes.forEach { it.consume() }
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
                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                shape = RectangleShape,
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Box {
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
                                                        val hitHighlight = page.highlights.firstOrNull { h ->
                                                            h.bounds.any { rect -> rect.contains(prX, prY) }
                                                        }
                                                        if (hitHighlight != null) {
                                                            val hlWords = page.words.filter { w ->
                                                                hitHighlight.bounds.any { r ->
                                                                    android.graphics.RectF.intersects(r, w.bounds)
                                                                }
                                                            }
                                                            textSelection = TextSelection(index, hlWords, hitHighlight)
                                                            return@detectTapGestures
                                                        }

                                                        // 2. Check text words
                                                        val hitWord = page.words.firstOrNull { w ->
                                                            w.bounds.contains(prX, prY)
                                                        }
                                                        if (hitWord != null) {
                                                            textSelection = TextSelection(index, listOf(hitWord))
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

                                    // Blue selection overlay — rendered in page-local space so it zooms/scrolls with the page
                                    val sel = textSelection
                                    if (sel != null && sel.pageIndex == index) {
                                        val displayedWords = dragWords ?: sel.selectedWords
                                        val overlayWords = if (sel.existingHighlight != null) sel.selectedWords else displayedWords

                                        Canvas(modifier = Modifier.matchParentSize()) {
                                            groupIntoLines(overlayWords).forEach { lineWords ->
                                                val l = lineWords.minOf { it.bounds.left }                     / page.nativeWidth  * size.width
                                                val t = lineWords.minOf { it.bounds.top - it.bounds.height() } / page.nativeHeight * size.height
                                                val r = lineWords.maxOf { it.bounds.right }                    / page.nativeWidth  * size.width
                                                val b = lineWords.maxOf { it.bounds.top }                      / page.nativeHeight * size.height
                                                drawRect(Color(0xFFBBDEFB), Offset(l, t), Size(r - l, b - t), blendMode = BlendMode.Multiply)
                                            }
                                            if (sel.existingHighlight == null) {
                                                val firstWord = displayedWords.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left }))
                                                val lastWord  = displayedWords.maxWithOrNull(compareBy({ it.bounds.bottom }, { it.bounds.right }))
                                                firstWord?.let {
                                                    drawCircle(Color(0xFF1565C0.toInt()), radius = 10.dp.toPx(),
                                                        center = Offset(it.bounds.left  / page.nativeWidth  * size.width,
                                                                        it.bounds.bottom / page.nativeHeight * size.height))
                                                }
                                                lastWord?.let {
                                                    drawCircle(Color(0xFF1565C0.toInt()), radius = 10.dp.toPx(),
                                                        center = Offset(it.bounds.right  / page.nativeWidth  * size.width,
                                                                        it.bounds.bottom / page.nativeHeight * size.height))
                                                }
                                            }
                                        }

                                        Box(modifier = Modifier.matchParentSize().pointerInput(sel) {
                                            val touchThreshPx = 32.dp.toPx()
                                            awaitEachGesture {
                                                val currentWords = dragWords ?: sel.selectedWords
                                                val firstWord = currentWords.minWithOrNull(compareBy({ it.bounds.top }, { it.bounds.left })) ?: return@awaitEachGesture
                                                val lastWord  = currentWords.maxWithOrNull(compareBy({ it.bounds.bottom }, { it.bounds.right })) ?: return@awaitEachGesture

                                                val startX = firstWord.bounds.left   / page.nativeWidth  * pageSize.width.toFloat()
                                                val startY = firstWord.bounds.bottom / page.nativeHeight * pageSize.height.toFloat()
                                                val endX   = lastWord.bounds.right   / page.nativeWidth  * pageSize.width.toFloat()
                                                val endY   = lastWord.bounds.bottom  / page.nativeHeight * pageSize.height.toFloat()

                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                val downPos = down.position
                                                val hitStart = (downPos - Offset(startX, startY)).getDistance() < touchThreshPx
                                                val hitEnd   = (downPos - Offset(endX, endY)).getDistance()   < touchThreshPx

                                                if (!hitStart && !hitEnd) {
                                                    textSelection = null
                                                    return@awaitEachGesture
                                                }
                                                down.consume()

                                                do {
                                                    val event = awaitPointerEvent()
                                                    event.changes.forEach { it.consume() }
                                                    val pos = event.changes.firstOrNull()?.position ?: continue
                                                    val prX = pos.x / pageSize.width.toFloat()  * page.nativeWidth
                                                    val prY = pos.y / pageSize.height.toFloat() * page.nativeHeight
                                                    val nearest = page.words.minByOrNull { w ->
                                                        val cx = (w.bounds.left + w.bounds.right)  / 2f
                                                        val cy = (w.bounds.top  + w.bounds.bottom) / 2f
                                                        (cx - prX) * (cx - prX) + (cy - prY) * (cy - prY)
                                                    } ?: continue
                                                    val lines = groupIntoLines(page.words)
                                                    val nearestCX = nearest.bounds.centerX()
                                                    dragWords = if (hitStart) {
                                                        val endWord = sel.selectedWords.last()
                                                        val endLineIdx = lines.indexOfFirst { line -> endWord in line }
                                                        val nearestLineIdx = lines.indexOfFirst { line -> nearest in line }
                                                        val clampedNearest = if (endLineIdx >= 0 && nearestLineIdx > endLineIdx)
                                                            lines[endLineIdx].minByOrNull { kotlin.math.abs(it.bounds.centerX() - nearestCX) } ?: nearest
                                                        else nearest
                                                        wordsFrom(clampedNearest, endWord, page.words)
                                                    } else {
                                                        val startWord = sel.selectedWords.first()
                                                        val startLineIdx = lines.indexOfFirst { line -> startWord in line }
                                                        val nearestLineIdx = lines.indexOfFirst { line -> nearest in line }
                                                        val clampedNearest = if (startLineIdx >= 0 && nearestLineIdx < startLineIdx)
                                                            lines[startLineIdx].minByOrNull { kotlin.math.abs(it.bounds.centerX() - nearestCX) } ?: nearest
                                                        else nearest
                                                        wordsFrom(startWord, clampedNearest, page.words)
                                                    }
                                                } while (event.changes.any { it.pressed })

                                                val committed = dragWords
                                                if (committed != null) {
                                                    textSelection = sel.copy(selectedWords = committed)
                                                    dragWords = null
                                                }
                                            }
                                        })

                                        if (displayedWords.isNotEmpty()) {
                                            val selTopPR    = displayedWords.minOf { it.bounds.top }
                                            val selCenterX  = (displayedWords.minOf { it.bounds.left } + displayedWords.maxOf { it.bounds.right }) / 2f
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
                                                                    viewModel.addHighlight(sel.pageIndex, sel.selectedWords)
                                                                    textSelection = null
                                                                }) { Text("Highlight") }
                                                            } else {
                                                                TextButton(onClick = {
                                                                    viewModel.deleteHighlight(sel.existingHighlight)
                                                                    textSelection = null
                                                                }) { Text("Delete") }
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
}
