package io.github.molnarandris.margin.ui.pdfviewer

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Viewer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                var scale by remember { mutableFloatStateOf(1f) }
                var offsetX by remember { mutableFloatStateOf(0f) }
                val lazyListState = rememberLazyListState()

                val density = LocalDensity.current
                val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
                val marginPx = with(density) { 8.dp.toPx() }
                val contentWidthDp = with(density) { (screenWidthPx * scale).toDp() }

                // PointerEventPass.Initial runs root→leaf, before the LazyColumn's
                // scroll (Main pass). We consume 2-finger events here so the
                // LazyColumn never sees them. Single-finger events are left
                // unconsumed so the LazyColumn's scroll works normally.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color(0xFFE0E0E0))
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
                                            if (spanChangePx > 8f) {
                                                // Pinch-to-zoom: apply zoom and keep centroid fixed
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

                                                // Keep the centroid fixed vertically.
                                                // LazyColumn anchors the first visible item's top;
                                                // all content below it scales by actualZoom.
                                                // The point at centroid.y is (centroid.y - anchorY)
                                                // below the anchor, so it moves by that distance
                                                // times (actualZoom - 1). Also apply two-finger
                                                // vertical pan via panChange.y.
                                                val anchorY = lazyListState.layoutInfo
                                                    .visibleItemsInfo.firstOrNull()?.offset?.toFloat() ?: 0f
                                                val scrollDelta = (centroid.y - anchorY) * (actualZoom - 1f) - panChange.y
                                                lazyListState.dispatchRawDelta(scrollDelta)
                                            } else {
                                                // Pure two-finger scroll: pan only, no zoom
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
                        itemsIndexed(state.pages) { index, bitmap ->
                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                                shape = RectangleShape,
                                colors = CardDefaults.cardColors(containerColor = Color.White)
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Page ${index + 1}",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
