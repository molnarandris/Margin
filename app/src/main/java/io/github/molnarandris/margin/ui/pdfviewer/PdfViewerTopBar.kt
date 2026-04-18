package io.github.molnarandris.margin.ui.pdfviewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfViewerTopBar(
    isSearchVisible: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    searchState: SearchState,
    pdfTitle: String,
    pdfAuthors: List<String>,
    previousDocParams: Pair<Uri, String>?,
    totalPages: Int,
    currentPage: Int,
    canUndo: Boolean,
    canRedo: Boolean,
    penThickness: StrokeThickness,
    penColor: StrokeColor,
    onBack: () -> Unit,
    onOpenPdf: (Uri, String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onPrevMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onCloseSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPenThicknessChange: (StrokeThickness) -> Unit,
    onPenColorChange: (StrokeColor) -> Unit,
    onEditMetadata: () -> Unit,
    onInsertPhoto: () -> Unit,
) {
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
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    placeholder = { Text("Search…") },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                        modifier = Modifier.weight(1f, fill = false).pointerInput(Unit) {
                            detectTapGestures(onLongPress = { onEditMetadata() })
                        }
                    ) {
                        Text(
                            text = if (pdfTitle.isNotEmpty()) pdfTitle else "No Title",
                            fontSize = 16.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val pageInfo = if (totalPages > 0) "${currentPage + 1} / $totalPages" else ""
                        val subtitle = listOfNotNull(
                            pdfAuthors.joinToString(", ").ifEmpty { null },
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
                    if (previousDocParams != null) {
                        IconButton(onClick = { onOpenPdf(previousDocParams.first, previousDocParams.second) }) {
                            Icon(Icons.Default.SwapHoriz, contentDescription = "Switch to previous document")
                        }
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
                IconButton(onClick = onPrevMatch) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = onNextMatch) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
                }
                IconButton(onClick = onCloseSearch) {
                    Icon(Icons.Default.Close, contentDescription = "Close search")
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(-8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onUndo, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = onRedo, enabled = canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                }
                Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                IconButton(onClick = onInsertPhoto) {
                    Icon(Icons.Default.PhotoCamera, contentDescription = "Insert photo")
                }
                Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    StrokeThickness.entries.forEach { t ->
                        ThicknessButton(t, t == penThickness) { onPenThicknessChange(t) }
                    }
                }
                Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                    StrokeColor.entries.forEach { c ->
                        ColorButton(c, c == penColor) { onPenColorChange(c) }
                    }
                }
                Box(Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))
                IconButton(onClick = onOpenSearch) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }
        }
    )
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
