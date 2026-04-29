package io.github.molnarandris.margin.ui.pdfviewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageOverviewScreen(
    pages: List<PdfPage>,
    inkStrokes: Map<Int, List<InkStroke>>,
    initialPage: Int,
    onClose: () -> Unit,
    onNavigateToPage: (Int) -> Unit,
    onDeletePages: (Set<Int>) -> Unit
) {
    var selectedPages by remember { mutableStateOf(emptySet<Int>()) }
    val isSelectionMode = selectedPages.isNotEmpty()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    LaunchedEffect(Unit) {
        if (initialPage > 0) gridState.scrollToItem(initialPage)
    }

    BackHandler(enabled = isSelectionMode) { selectedPages = emptySet() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = if (isSelectionMode) "${selectedPages.size} selected" else "Pages"
                    )
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { selectedPages = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel selection")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete selected")
                        }
                    } else {
                        IconButton(onClick = onClose) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
            )

            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                itemsIndexed(pages) { index, page ->
                    ThumbnailItem(
                        page = page,
                        pageNumber = index + 1,
                        strokes = inkStrokes[index].orEmpty(),
                        isSelected = index in selectedPages,
                        isSelectionMode = isSelectionMode,
                        onTap = {
                            if (isSelectionMode) {
                                selectedPages = if (index in selectedPages)
                                    selectedPages - index
                                else
                                    selectedPages + index
                            } else {
                                onNavigateToPage(index)
                            }
                        },
                        onLongPress = { selectedPages = selectedPages + index }
                    )
                }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("Delete ${selectedPages.size} page${if (selectedPages.size == 1) "" else "s"}?") },
                confirmButton = {
                    TextButton(onClick = {
                        onDeletePages(selectedPages)
                        showDeleteConfirm = false
                    }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun ThumbnailItem(
    page: PdfPage,
    pageNumber: Int,
    strokes: List<InkStroke>,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit
) {
    val aspectRatio = if (page.nativeHeight > 0)
        page.nativeWidth.toFloat() / page.nativeHeight.toFloat()
    else
        0.707f  // A4 fallback

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(isSelectionMode) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { onLongPress() }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(
                    width = if (isSelected) 3.dp else 0.5.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(4.dp)
                )
        ) {
            Image(
                bitmap = page.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxSize()
            )

            if (strokes.isNotEmpty()) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val baseStrokePx = size.width / page.nativeWidth
                    for (stroke in strokes) {
                        val pts = stroke.points
                        if (pts.size < 2) continue
                        val c = stroke.color.composeColor
                        val w = baseStrokePx * stroke.thickness.multiplier
                        if (pts.first() == pts.last()) {
                            drawCircle(c, radius = w / 2f,
                                center = Offset(pts.first().x * size.width, pts.first().y * size.height))
                        } else {
                            val pxPts = pts.map { Offset(it.x * size.width, it.y * size.height) }
                            val path = catmullRomPath(pxPts)
                            val cap = if (stroke.roundCap) StrokeCap.Round else StrokeCap.Butt
                            val join = if (stroke.roundCap) StrokeJoin.Round else StrokeJoin.Miter
                            drawPath(path, color = c, style = Stroke(width = w, cap = cap, join = join))
                        }
                    }
                }
            }

            if (isSelected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        Text(
            text = "$pageNumber",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
