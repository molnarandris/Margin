package io.github.molnarandris.margin.ui.pdfviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

internal data class PageTextSelection(
    val pageIndex: Int,
    val selectedChars: List<TextChar>,
    val existingHighlight: PdfHighlight? = null
)

internal data class InkStrokeSelection(
    val pageIndex: Int,
    val strokes: List<InkStroke>,
    val bounds: Rect,
    val dragOffsetPx: Offset = Offset.Zero
)

internal data class DestinationHighlight(val pageIndex: Int, val x: Float, val y: Float)

internal data class PdfPageState(
    val page: PdfPage,
    val index: Int,
    val inkStrokes: List<InkStroke>,
    val penColor: StrokeColor,
    val penThickness: StrokeThickness,
    val inkStrokeSelection: InkStrokeSelection?,
    val inkClipboard: List<InkStroke>?,
    val searchState: SearchState,
    val textSelection: PageTextSelection?,
    val dragChars: List<TextChar>?,
    val destinationHighlight: DestinationHighlight?,
    val popupHeightPx: Int,
)

internal class PdfPageActions(
    val onBarsVisibleToggle: () -> Unit,
    val onTextSelectionChanged: (PageTextSelection?) -> Unit,
    val onDragCharsChanged: (List<TextChar>?) -> Unit,
    val onIsDraggingHandleChanged: (Boolean) -> Unit,
    val onPopupHeightPxChanged: (Int) -> Unit,
    val onAddHighlight: (PageTextSelection) -> Unit,
    val onDeleteHighlight: (PdfHighlight) -> Unit,
    val onAnnotateHighlight: (PdfHighlight) -> Unit,
    val onTapAnnotatedHighlight: (PdfHighlight) -> Unit,
    val onCopy: (String) -> Unit,
    val onLinkTap: (LinkTarget, android.graphics.RectF) -> Unit,
    val onEraseInkStrokes: (List<Int>) -> Unit,
    val onAddInkAnnotation: (List<Offset>, Int, Int) -> Int,
    val onStrokeSelectionChanged: (InkStrokeSelection?) -> Unit,
    val onSelectionDragDelta: (Offset) -> Unit,
    val onCommitSelectionMove: (List<InkStroke>, Offset, IntSize) -> Unit,
    val onCopyInkStrokes: (List<InkStroke>) -> Unit,
    val onPasteInkStrokes: (Offset) -> Unit,
    val onDeletePage: () -> Unit,
    val onInsertPageBefore: () -> Unit,
    val onInsertPageAfter: () -> Unit,
)

@Composable
internal fun PdfAnnotationLayer(
    state: PdfPageState,
    actions: PdfPageActions,
    modifier: Modifier = Modifier,
) {
    val page = state.page
    val density = LocalDensity.current
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    var currentInkStroke by remember { mutableStateOf<List<Offset>?>(null) }
    var showPageContextMenu by remember { mutableStateOf(false) }
    var contextMenuOffset by remember { mutableStateOf(Offset.Zero) }
    var contextMenuSize by remember { mutableStateOf(IntSize.Zero) }
    var selectionMenuSize by remember { mutableStateOf(IntSize.Zero) }
    val selectionMenuSizeRef = rememberUpdatedState(selectionMenuSize)
    val inkStrokesRef = rememberUpdatedState(state.inkStrokes)
    val inkStrokeSelectionRef = rememberUpdatedState(state.inkStrokeSelection)
    val currentSelectionRef = rememberUpdatedState(state.textSelection)
    val actionsRef = rememberUpdatedState(actions)
    val indexRef = rememberUpdatedState(state.index)
    val pageScope = rememberCoroutineScope()

    Box(modifier = modifier
        .onSizeChanged { pageSize = it }
        .pointerInput(page.nativeWidth, page.nativeHeight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val currentIndex = indexRef.value
                val currentActions = actionsRef.value

                // ── Handle active selection (both stylus and finger) ──────────────
                val sel = inkStrokeSelectionRef.value
                if (sel != null && sel.pageIndex == currentIndex) {
                    val actualBounds = sel.bounds.translate(sel.dragOffsetPx)
                    if (actualBounds.contains(down.position)) {
                        // Move gesture: pen/finger inside selection box
                        down.consume()
                        var prevPos = down.position
                        var totalDelta = Offset.Zero
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.find { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (totalDelta != Offset.Zero)
                                    currentActions.onCommitSelectionMove(sel.strokes, totalDelta, pageSize)
                                break
                            }
                            change.consume()
                            val delta = change.position - prevPos
                            prevPos = change.position
                            totalDelta += delta
                            currentActions.onSelectionDragDelta(delta)
                        }
                        return@awaitEachGesture
                    } else {
                        // Tap outside: deselect unless the tap landed on the selection action menu
                        val menuSz = selectionMenuSizeRef.value
                        val tappedMenu = if (menuSz != IntSize.Zero) {
                            val gapPx = 8.dp.roundToPx()
                            val b2 = sel.bounds.translate(sel.dragOffsetPx)
                            val mX = (b2.center.x - menuSz.width / 2f).roundToInt()
                                .coerceIn(0, (pageSize.width - menuSz.width).coerceAtLeast(0)).toFloat()
                            val mY = (b2.top - gapPx - menuSz.height).roundToInt()
                                .coerceAtLeast(0).toFloat()
                            Rect(mX, mY, mX + menuSz.width, mY + menuSz.height)
                                .contains(down.position)
                        } else false
                        if (!tappedMenu) {
                            currentActions.onStrokeSelectionChanged(null)
                        }
                        return@awaitEachGesture
                    }
                }

                if (down.type != PointerType.Stylus && down.type != PointerType.Eraser) return@awaitEachGesture
                down.consume()

                // ── Phase A: Collect stroke points, detect lasso closure ──────────
                val points = mutableListOf(down.position)
                currentInkStroke = listOf(down.position)
                var lassoClosedAt: Long? = null
                var triggerLasso = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.find { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    points.add(change.position)
                    currentInkStroke = points.toList()
                    if (isApproxClosed(points)) {
                        if (lassoClosedAt == null) lassoClosedAt = System.currentTimeMillis()
                        if (System.currentTimeMillis() - lassoClosedAt >= 600L) {
                            triggerLasso = true
                            break
                        }
                    } else {
                        lassoClosedAt = null
                    }
                }
                if (triggerLasso) currentInkStroke = null  // Clear for lasso; ink path clears after async

                if (triggerLasso && pageSize != IntSize.Zero) {
                    // ── Phase B: Compute selection ────────────────────────────────
                    val pageStrokes = inkStrokesRef.value
                    val selected = pageStrokes.filter {
                        fractionInsidePolygon(it, points, pageSize) >= 0.80f
                    }
                    if (selected.isNotEmpty()) {
                        val bounds = computeSelectionBounds(selected, pageSize)
                        currentActions.onStrokeSelectionChanged(InkStrokeSelection(currentIndex, selected, bounds))
                    }
                    return@awaitEachGesture  // Never becomes an ink stroke
                }

                // ── Normal stroke / scribble logic ────────────────────────────────
                if (pageSize != IntSize.Zero) {
                    val capturedPoints = if (points.size < 2) listOf(points[0], points[0]) else points.toList()
                    val capturedPageStrokes = inkStrokesRef.value
                    val capturedPageSize = pageSize
                    val capturedActions = actionsRef.value

                    // Add the stroke to completedInkStrokes immediately so it stays visible
                    // when the next gesture overwrites currentInkStroke.
                    val addedId = capturedActions.onAddInkAnnotation(capturedPoints, capturedPageSize.width, capturedPageSize.height)
                    currentInkStroke = null

                    // Run O(m×n) scribble detection on Default so the gesture loop restarts
                    // immediately and the user's next stroke is not delayed.
                    pageScope.launch(Dispatchers.Default) {
                        val normalizedPoints = capturedPoints.map {
                            Offset(it.x / capturedPageSize.width, it.y / capturedPageSize.height)
                        }
                        val scribble = isScribble(capturedPoints)
                        if (scribble.isScribble) {
                            val hull = convexHull(scribble.reversalPoints)
                            val intersecting = capturedPageStrokes.filter {
                                strokeIntersectsScribble(it.points, normalizedPoints) ||
                                strokeNearScribble(it.points, capturedPoints, capturedPageSize, 10f) ||
                                fractionInsidePolygon(it, hull, capturedPageSize) >= 0.8f
                            }
                            if (intersecting.isNotEmpty()) {
                                // Erase intersected strokes plus the scribble itself
                                withContext(Dispatchers.Main) {
                                    capturedActions.onEraseInkStrokes(intersecting.map { it.id } + addedId)
                                }
                            }
                        }
                        // Not a scribble, or scribble with nothing nearby → stroke stays as-is
                    }
                    // awaitEachGesture restarts immediately; scribble detection finishes in background
                }
            }
        }
    ) {
        // Tap / long-press handler on the page surface
        Box(
            modifier = Modifier.matchParentSize()
                .pointerInput(page.links, page.words, page.highlights) {
                    detectTapGestures(
                        onDoubleTap = { actions.onBarsVisibleToggle() },
                        onTap = { tapOffset ->
                            if (showPageContextMenu) {
                                showPageContextMenu = false
                                return@detectTapGestures
                            }
                            if (currentSelectionRef.value != null) {
                                actions.onTextSelectionChanged(null)
                                return@detectTapGestures
                            }
                            if (pageSize == IntSize.Zero) return@detectTapGestures
                            val pdfX = tapOffset.x / pageSize.width * page.nativeWidth
                            val pdfY = tapOffset.y / pageSize.height * page.nativeHeight
                            val hit = page.links.firstOrNull { link ->
                                link.bounds.any { rect -> rect.contains(pdfX, pdfY) }
                            }
                            val annotatedHit = page.highlights.firstOrNull { h ->
                                h.note != null && h.bounds.any { r ->
                                    r.left <= pdfX && pdfX <= r.right &&
                                    r.top <= pdfY && pdfY <= r.bottom + r.height() * 0.3f
                                }
                            }
                            if (annotatedHit != null) {
                                actions.onTapAnnotatedHighlight(annotatedHit)
                                return@detectTapGestures
                            }
                            when (val target = hit?.target) {
                                is LinkTarget.Url -> actions.onLinkTap(target, hit.bounds.first())
                                is LinkTarget.Goto -> actions.onLinkTap(target, hit.bounds.first())
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
                                actions.onTextSelectionChanged(PageTextSelection(state.index, hlChars, hitHighlight))
                                return@detectTapGestures
                            }
                            val hitWord = page.words.firstOrNull { w ->
                                val visualTop = w.bounds.top - w.bounds.height()
                                prX >= w.bounds.left && prX <= w.bounds.right &&
                                prY >= visualTop && prY <= w.bounds.bottom
                            }
                            if (hitWord != null) {
                                actions.onTextSelectionChanged(PageTextSelection(state.index, hitWord.chars))
                            } else {
                                contextMenuOffset = longPressOffset
                                showPageContextMenu = true
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
                page.highlights.forEach { h ->
                    if (h.note == null) return@forEach
                    val firstBound = h.bounds.firstOrNull() ?: return@forEach
                    val cx = firstBound.right / page.nativeWidth  * size.width
                    val cy = firstBound.top   / page.nativeHeight * size.height
                    drawCircle(Color(0xFFFF9800), radius = 5.dp.toPx(), center = Offset(cx, cy))
                }
            }
        }
        val highlight = state.destinationHighlight
        if (highlight != null && highlight.pageIndex == state.index && pageSize != IntSize.Zero) {
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
        val pageSearchMatches = state.searchState.matches.filter { it.pageIndex == state.index }
        val currentMatch = state.searchState.matches.getOrNull(state.searchState.currentIndex)
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
        val activeSel = state.inkStrokeSelection?.takeIf { it.pageIndex == state.index }
        val selIds = activeSel?.strokes?.map { it.id }?.toSet() ?: emptySet()
        val dragPx = activeSel?.dragOffsetPx ?: Offset.Zero
        if (state.inkStrokes.isNotEmpty()) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val baseStrokePx = size.width / page.nativeWidth
                for (stroke in state.inkStrokes) {
                    val pts = stroke.points
                    if (pts.size < 2) continue
                    val c = stroke.color.composeColor
                    val w = baseStrokePx * stroke.thickness.multiplier
                    val dxNorm = if (stroke.id in selIds) dragPx.x / size.width  else 0f
                    val dyNorm = if (stroke.id in selIds) dragPx.y / size.height else 0f
                    val x0 = (pts.first().x + dxNorm) * size.width
                    val y0 = (pts.first().y + dyNorm) * size.height
                    if (pts.first() == pts.last()) {
                        drawCircle(c, radius = w / 2f, center = Offset(x0, y0))
                    } else {
                        val pxPts = pts.map { Offset((it.x + dxNorm) * size.width, (it.y + dyNorm) * size.height) }
                        val path = catmullRomPath(pxPts)
                        val cap = if (stroke.roundCap) StrokeCap.Round else StrokeCap.Butt
                        val join = if (stroke.roundCap) StrokeJoin.Round else StrokeJoin.Miter
                        drawPath(path, color = c, style = Stroke(width = w, cap = cap, join = join))
                    }
                }
            }
        }
        if (activeSel != null) {
            val b = activeSel.bounds.translate(activeSel.dragOffsetPx)
            Canvas(modifier = Modifier.matchParentSize()) {
                drawRect(
                    color = Color(0xFF00BCD4.toInt()),
                    topLeft = Offset(b.left, b.top),
                    size = Size(b.width, b.height),
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
                    )
                )
            }
            with(density) {
                val gapPx = 8.dp.roundToPx()
                Box(
                    modifier = Modifier
                        .offset {
                            val menuX = (b.center.x - selectionMenuSize.width / 2f).roundToInt()
                                .coerceIn(0, (pageSize.width - selectionMenuSize.width).coerceAtLeast(0))
                            val menuY = (b.top - gapPx - selectionMenuSize.height).roundToInt().coerceAtLeast(0)
                            IntOffset(menuX, menuY)
                        }
                        .onSizeChanged { selectionMenuSize = it }
                ) {
                    Card(
                        elevation = CardDefaults.cardElevation(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)) {
                            IconButton(onClick = {
                                actions.onCopyInkStrokes(activeSel.strokes)
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                            }
                            IconButton(onClick = {
                                actions.onCopyInkStrokes(activeSel.strokes)
                                actions.onEraseInkStrokes(activeSel.strokes.map { it.id })
                                actions.onStrokeSelectionChanged(null)
                            }) {
                                Icon(Icons.Default.ContentCut, contentDescription = "Cut")
                            }
                            IconButton(onClick = {
                                actions.onEraseInkStrokes(activeSel.strokes.map { it.id })
                                actions.onStrokeSelectionChanged(null)
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete")
                            }
                        }
                    }
                }
            }
        }
        val inkStroke = currentInkStroke
        if (inkStroke != null && inkStroke.size >= 2) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val baseStrokePx = size.width / page.nativeWidth
                val c = state.penColor.composeColor
                val w = baseStrokePx * state.penThickness.multiplier
                if (inkStroke.first() == inkStroke.last()) {
                    drawCircle(c, radius = w / 2f, center = inkStroke.first())
                } else {
                    val path = catmullRomPath(inkStroke)
                    drawPath(path, color = c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        val sel = state.textSelection
        if (sel != null && sel.pageIndex == state.index) {
            val displayedChars = state.dragChars ?: sel.selectedChars
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
                    val currentChars = state.dragChars ?: sel.selectedChars
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
                        actions.onTextSelectionChanged(null)
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
                    actions.onIsDraggingHandleChanged(true)
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
                            actions.onDragCharsChanged(newDragChars)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        actions.onIsDraggingHandleChanged(false)
                    }
                    val committed = lastDragChars
                    if (committed != null) {
                        actions.onTextSelectionChanged(sel.copy(selectedChars = committed))
                        actions.onDragCharsChanged(null)
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
                                popupLocalY - gapPx - state.popupHeightPx
                            )
                        }
                        .onSizeChanged { actions.onPopupHeightPxChanged(it.height) }
                    ) {
                        Card(
                            elevation = CardDefaults.cardElevation(4.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White)
                        ) {
                            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)) {
                                if (sel.existingHighlight == null) {
                                    IconButton(onClick = {
                                        actions.onAddHighlight(sel)
                                        actions.onTextSelectionChanged(null)
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
                                        actions.onDeleteHighlight(sel.existingHighlight)
                                        actions.onTextSelectionChanged(null)
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
                                if (sel.existingHighlight != null) {
                                    IconButton(onClick = { actions.onAnnotateHighlight(sel.existingHighlight) }) {
                                        Icon(Icons.AutoMirrored.Filled.StickyNote2, contentDescription = "Add note")
                                    }
                                }
                                IconButton(onClick = {
                                    val selectedSet = sel.selectedChars.toHashSet()
                                    val text = page.words
                                        .filter { w -> w.chars.any { it in selectedSet } }
                                        .joinToString(" ") { w ->
                                            w.chars.filter { it in selectedSet }.joinToString("") { it.text }
                                        }
                                    actions.onCopy(text)
                                    actions.onTextSelectionChanged(null)
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showPageContextMenu) {
            with(density) {
                val gapPx = 8.dp.roundToPx()
                Box(
                    modifier = Modifier
                        .offset {
                            val menuX = (contextMenuOffset.x - contextMenuSize.width / 2f).roundToInt()
                                .coerceIn(0, (pageSize.width - contextMenuSize.width).coerceAtLeast(0))
                            val menuY = (contextMenuOffset.y - gapPx - contextMenuSize.height).roundToInt().coerceAtLeast(0)
                            IntOffset(menuX, menuY)
                        }
                        .onSizeChanged { contextMenuSize = it }
                ) {
                    Card(
                        elevation = CardDefaults.cardElevation(4.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.dp)) {
                            IconButton(onClick = {
                                showPageContextMenu = false
                                actions.onDeletePage()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete page")
                            }
                            IconButton(onClick = {
                                showPageContextMenu = false
                                actions.onInsertPageBefore()
                            }) {
                                InsertPageIcon(before = true)
                            }
                            IconButton(onClick = {
                                showPageContextMenu = false
                                actions.onInsertPageAfter()
                            }) {
                                InsertPageIcon(before = false)
                            }
                            if (!state.inkClipboard.isNullOrEmpty()) {
                                IconButton(onClick = {
                                    showPageContextMenu = false
                                    val centerNorm = Offset(
                                        contextMenuOffset.x / pageSize.width,
                                        contextMenuOffset.y / pageSize.height
                                    )
                                    actions.onPasteInkStrokes(centerNorm)
                                }) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper: group chars into lines by proximity of their top coordinates.
private fun groupIntoLines(chars: List<TextChar>): List<List<TextChar>> {
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
private fun charsFrom(start: TextChar, end: TextChar, allChars: List<TextChar>): List<TextChar> {
    val startIdx = allChars.indexOf(start).takeIf { it >= 0 } ?: return listOf(start)
    val endIdx   = allChars.indexOf(end).takeIf   { it >= 0 } ?: return listOf(end)
    val lo = minOf(startIdx, endIdx)
    val hi = maxOf(startIdx, endIdx)
    return allChars.subList(lo, hi + 1)
}

@Composable
private fun InsertPageIcon(before: Boolean) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = Modifier.size(width = 34.dp, height = 26.dp)) {
        val lineH = size.height
        val rectW = size.width * 0.48f
        val rectH = size.height
        val gap = size.width * 0.08f
        val lineX: Float
        val rectLeft: Float
        if (before) {
            lineX = 0f
            rectLeft = gap + 2f
        } else {
            rectLeft = 0f
            lineX = rectLeft + rectW + gap + 2f
        }
        drawLine(color = color, start = Offset(lineX, 0f), end = Offset(lineX, lineH), strokeWidth = 2.dp.toPx())
        drawRect(color = color, topLeft = Offset(rectLeft, 0f), size = Size(rectW, rectH), style = Stroke(width = 1.5.dp.toPx()))
        val cx = rectLeft + rectW / 2f
        val cy = rectH / 2f
        val arm = rectW * 0.25f
        drawLine(color = color, start = Offset(cx - arm, cy), end = Offset(cx + arm, cy), strokeWidth = 1.5.dp.toPx())
        drawLine(color = color, start = Offset(cx, cy - arm), end = Offset(cx, cy + arm), strokeWidth = 1.5.dp.toPx())
    }
}
