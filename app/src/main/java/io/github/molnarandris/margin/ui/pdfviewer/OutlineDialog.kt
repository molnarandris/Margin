package io.github.molnarandris.margin.ui.pdfviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun OutlinePanel(
    outline: List<OutlineItem>,
    collapsed: Set<Int>,
    tocContextMenuIndex: Int?,
    currentPage: Int,
    onDismiss: () -> Unit,
    onCollapsedChange: (Set<Int>) -> Unit,
    onContextMenuChange: (Int?) -> Unit,
    onNavigate: (pageIndex: Int) -> Unit,
    onUpdateOutline: (List<OutlineItem>) -> Unit,
    onRenameClick: (index: Int, title: String) -> Unit,
    onDeleteClick: (index: Int) -> Unit,
    onDeleteWithUndo: (newList: List<OutlineItem>, snapshot: List<OutlineItem>) -> Unit,
) {
    val visibleItems = remember(outline, collapsed) {
        buildList {
            var collapseAtLevel = -1
            outline.forEachIndexed { i, item ->
                if (collapseAtLevel >= 0 && item.level > collapseAtLevel) return@forEachIndexed
                collapseAtLevel = -1
                add(i to item)
                if (item.hasChildren && i in collapsed) collapseAtLevel = item.level
            }
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogWindowProvider = LocalView.current.parent as? androidx.compose.ui.window.DialogWindowProvider
        SideEffect {
            dialogWindowProvider?.window?.setDimAmount(0.15f)
        }
        val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceAtLeast(240.dp)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onDismiss() }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(0.65f)
                    .height(maxHeight)
                    .clickable(enabled = false, onClick = {}),
                shape = RoundedCornerShape(topEnd = 16.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                LazyColumn(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                    items(visibleItems, key = { it.first }) { (index, item) ->
                        Column(modifier = Modifier.fillMaxWidth().animateItem()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onNavigate(item.pageIndex) },
                                        onLongClick = {
                                            onContextMenuChange(if (tocContextMenuIndex == index) null else index)
                                        }
                                    )
                                    .padding(
                                        start = (16 + item.level * 24).dp,
                                        end = 8.dp, top = 12.dp, bottom = 12.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.hasChildren) {
                                    Icon(
                                        imageVector = if (index in collapsed)
                                            Icons.AutoMirrored.Filled.KeyboardArrowRight
                                        else
                                            Icons.Default.KeyboardArrowDown,
                                        contentDescription = if (index in collapsed) "Expand" else "Collapse",
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clickable {
                                                onCollapsedChange(
                                                    if (index in collapsed) collapsed - index else collapsed + index
                                                )
                                            }
                                    )
                                }
                                DotLeaderOutlineText(
                                    title = item.title,
                                    pageNum = item.pageIndex + 1,
                                    fontWeight = if (item.level == 0) FontWeight.Bold else null,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (tocContextMenuIndex == index) {
                                val groupEnd = run {
                                    var e = index + 1
                                    while (e < outline.size && outline[e].level > item.level) e++
                                    e
                                }
                                val prevGroupStart = (0 until index).lastOrNull { outline[it].level <= item.level }
                                val nextSiblingStart = groupEnd.takeIf { it < outline.size }
                                val maxLevel = if (index > 0) outline[index - 1].level + 1 else 0
                                Surface(
                                    tonalElevation = 4.dp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = (16 + item.level * 24).dp, end = 8.dp, bottom = 4.dp)
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        IconButton(
                                            enabled = prevGroupStart != null,
                                            onClick = {
                                                val ps = prevGroupStart ?: return@IconButton
                                                val newList = outline.toMutableList()
                                                val group = newList.subList(index, groupEnd).toList()
                                                val before = newList.subList(ps, index).toList()
                                                repeat(groupEnd - ps) { newList.removeAt(ps) }
                                                newList.addAll(ps, before)
                                                newList.addAll(ps, group)
                                                onUpdateOutline(newList)
                                                onContextMenuChange(ps)
                                            }
                                        ) { Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up") }
                                        IconButton(
                                            enabled = nextSiblingStart != null,
                                            onClick = {
                                                val ns = nextSiblingStart ?: return@IconButton
                                                var nsEnd = ns + 1
                                                while (nsEnd < outline.size && outline[nsEnd].level > outline[ns].level) nsEnd++
                                                val newList = outline.toMutableList()
                                                val group = newList.subList(index, groupEnd).toList()
                                                val after = newList.subList(groupEnd, nsEnd).toList()
                                                repeat(nsEnd - index) { newList.removeAt(index) }
                                                newList.addAll(index, after)
                                                newList.addAll(index + after.size, group)
                                                onUpdateOutline(newList)
                                                onContextMenuChange(index + after.size)
                                            }
                                        ) { Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down") }
                                        IconButton(
                                            enabled = item.level > 0,
                                            onClick = {
                                                val newList = outline.toMutableList()
                                                for (i in index until groupEnd)
                                                    newList[i] = newList[i].copy(level = (newList[i].level - 1).coerceAtLeast(0))
                                                onUpdateOutline(newList)
                                            }
                                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Decrease level") }
                                        IconButton(
                                            enabled = item.level < maxLevel,
                                            onClick = {
                                                val newList = outline.toMutableList()
                                                for (i in index until groupEnd)
                                                    newList[i] = newList[i].copy(level = newList[i].level + 1)
                                                onUpdateOutline(newList)
                                            }
                                        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Increase level") }
                                        IconButton(onClick = {
                                            onRenameClick(index, item.title)
                                        }) { Icon(Icons.Default.Edit, contentDescription = "Rename") }
                                        IconButton(onClick = {
                                            if (item.hasChildren) {
                                                onDeleteClick(index)
                                            } else {
                                                val snapshot = outline.toList()
                                                val newList = outline.toMutableList()
                                                newList.removeAt(index)
                                                onContextMenuChange(null)
                                                onDeleteWithUndo(newList, snapshot)
                                            }
                                        }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocRenameDialog(
    index: Int,
    initialTitle: String,
    outline: List<OutlineItem>,
    onUpdateOutline: (List<OutlineItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename TOC entry") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newList = outline.toMutableList()
                newList[index] = newList[index].copy(title = text)
                onUpdateOutline(newList)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocDeleteDialog(
    index: Int,
    outline: List<OutlineItem>,
    onDeleteWithUndo: (newList: List<OutlineItem>, snapshot: List<OutlineItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    val deletedItem = outline[index]
    val groupEnd = run {
        var e = index + 1
        while (e < outline.size && outline[e].level > deletedItem.level) e++
        e
    }
    fun doDelete(newList: List<OutlineItem>) {
        val snapshot = outline.toList()
        onDeleteWithUndo(newList, snapshot)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${deletedItem.title}\"") },
        text = {
            Column {
                Text("This entry has children. Choose what to do with them.")
                Spacer(Modifier.height(16.dp))
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        doDelete(outline.toMutableList().also { it.subList(index, groupEnd).clear() })
                    }
                ) { Text("Delete including children") }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val newList = outline.toMutableList()
                        newList.removeAt(index)
                        for (i in index until index + (groupEnd - index - 1))
                            newList[i] = newList[i].copy(level = newList[i].level - 1)
                        doDelete(newList)
                    }
                ) { Text("Delete entry, promote children") }
                if (deletedItem.level > 0) {
                    TextButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            doDelete(outline.toMutableList().also { it.removeAt(index) })
                        }
                    ) { Text("Delete entry, keep children level") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTocDialog(
    pageForToc: Int,
    outline: List<OutlineItem>,
    onUpdateOutline: (List<OutlineItem>) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf("Page ${pageForToc + 1}") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to table of contents") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newItem = OutlineItem(title = title, pageIndex = pageForToc, level = 0)
                val insertAt = outline.indexOfFirst { it.pageIndex > pageForToc }
                val newList = outline.toMutableList()
                if (insertAt == -1) newList.add(newItem) else newList.add(insertAt, newItem)
                onUpdateOutline(newList)
                onDismiss()
            }) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun DotLeaderOutlineText(
    title: String,
    pageNum: Int,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
) {
    val dotColor = Color.Gray.copy(alpha = 0.45f)
    SubcomposeLayout(modifier) { constraints ->
        val pageNumPlaceable = subcompose("pageNum") {
            Text(text = "$pageNum", color = Color.Gray, fontSize = 12.sp)
        }[0].measure(Constraints())

        val minDotGap = 20
        val maxTitleWidth = (constraints.maxWidth - pageNumPlaceable.width - minDotGap).coerceAtLeast(0)
        val titlePlaceable = subcompose("title") {
            Text(text = title, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }[0].measure(Constraints(maxWidth = maxTitleWidth))

        val height = maxOf(titlePlaceable.height, pageNumPlaceable.height)
        val dotsStart = titlePlaceable.width
        val dotsEnd = constraints.maxWidth - pageNumPlaceable.width
        val dotsWidth = (dotsEnd - dotsStart).coerceAtLeast(0)

        val dotsPlaceable = subcompose("dots") {
            Canvas(Modifier.fillMaxSize()) {
                val y = size.height * 0.78f
                drawLine(
                    color = dotColor,
                    start = Offset(4.dp.toPx(), y),
                    end = Offset(size.width - 4.dp.toPx(), y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx()), 0f)
                )
            }
        }[0].measure(Constraints.fixed(dotsWidth, height))

        layout(constraints.maxWidth, height) {
            titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
            dotsPlaceable.placeRelative(dotsStart, 0)
            pageNumPlaceable.placeRelative(
                constraints.maxWidth - pageNumPlaceable.width,
                (height - pageNumPlaceable.height) / 2
            )
        }
    }
}
