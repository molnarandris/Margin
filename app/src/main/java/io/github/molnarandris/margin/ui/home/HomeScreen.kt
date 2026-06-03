package io.github.molnarandris.margin.ui.home

import android.net.Uri
import android.provider.DocumentsContract
import io.github.molnarandris.margin.ui.common.EditMetadataDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.molnarandris.margin.data.FileSystemItem
import io.github.molnarandris.margin.data.PdfFile
import io.github.molnarandris.margin.data.PdfType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPdf: (dirUri: Uri, docUri: Uri) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val typeFilter by viewModel.typeFilter.collectAsState()
    val knownAuthors by viewModel.knownAuthors.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var isSearchActive by remember { mutableStateOf(searchQuery.isNotBlank()) }
    val searchFocusRequester = remember { FocusRequester() }
    var fileNotFoundPdf by remember { mutableStateOf<PdfFile?>(null) }

    LaunchedEffect(isSearchActive) {
        if (isSearchActive) searchFocusRequester.requestFocus()
    }

    fileNotFoundPdf?.let { pdf ->
        AlertDialog(
            onDismissRequest = { fileNotFoundPdf = null },
            title = { Text("File not found") },
            text = { Text("\"${pdf.name}\" no longer exists on the device.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFromDatabase(pdf.uri)
                    fileNotFoundPdf = null
                }) { Text("Remove from database") }
            },
            dismissButton = {
                TextButton(onClick = { fileNotFoundPdf = null }) { Text("Back") }
            }
        )
    }

    LaunchedEffect(Unit) {
        viewModel.openPdfEvent.collect { docUri ->
            val ready = viewModel.uiState.value as? HomeUiState.Ready ?: return@collect
            onOpenPdf(ready.rootUri, docUri)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshContents()
                val s = viewModel.sortOrder.value
                if (s == SortOrder.BY_RECENT) {
                    scope.launch { listState.scrollToItem(0) }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importPdf(uri)
    }

    Scaffold(
        topBar = {
            if (isSearchActive) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            placeholder = { Text("Search title, author, filename…") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester)
                        )
                    },
                    actions = {
                        IconButton(onClick = {
                            isSearchActive = false
                            viewModel.setSearchQuery("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close search")
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                "Margin",
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    letterSpacing = 0.04.em
                                )
                            )
                            if (uiState is HomeUiState.Ready) {
                                TextButton(onClick = { viewModel.openScratchpad() }) {
                                    Text("Scratchpad")
                                }
                                TypeFilterToggle(
                                    selected = typeFilter,
                                    onSelect = { viewModel.setTypeFilter(it) }
                                )
                            }
                        }
                    },
                    navigationIcon = {},
                    actions = {
                        if (uiState is HomeUiState.Ready) {
                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Search")
                            }
                            var showSortMenu by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(Icons.Default.SwapVert, contentDescription = "Sort")
                                }
                                DropdownMenu(
                                    expanded = showSortMenu,
                                    onDismissRequest = { showSortMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Name") },
                                        onClick = { viewModel.setSortOrder(SortOrder.BY_NAME); showSortMenu = false },
                                        trailingIcon = if (sortOrder == SortOrder.BY_NAME) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Recent") },
                                        onClick = { viewModel.setSortOrder(SortOrder.BY_RECENT); showSortMenu = false },
                                        trailingIcon = if (sortOrder == SortOrder.BY_RECENT) {
                                            { Icon(Icons.Default.Check, contentDescription = null) }
                                        } else null
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { viewModel.syncFilesystem() },
                            enabled = !isRefreshing
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Sync with filesystem")
                            }
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (uiState is HomeUiState.Ready) {
                SplitFab(
                    onImport = { pdfPicker.launch(arrayOf("application/pdf")) },
                    onNewNote = { viewModel.createNote() }
                )
            }
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is HomeUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            is HomeUiState.NoDirectory -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Choose a folder where your documents will be stored.")
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onOpenSettings) {
                        Text("Choose Directory")
                    }
                }
            }

            is HomeUiState.Ready -> {
                Column(modifier = Modifier.padding(innerPadding)) {
                    if (state.items.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No PDFs yet. Tap + to import one.")
                        }
                    } else {
                        ContentList(
                            items = state.items,
                            rootUri = state.rootUri,
                            searchQuery = searchQuery,
                            listState = listState,
                            onPdfClick = { pdf ->
                                scope.launch {
                                    val exists = withContext(Dispatchers.IO) {
                                        DocumentFile.fromSingleUri(context, pdf.uri)?.exists() == true
                                    }
                                    if (exists) onOpenPdf(state.rootUri, pdf.uri)
                                    else fileNotFoundPdf = pdf
                                }
                            },
                            onPdfDelete = { viewModel.deleteItem(it.uri) },
                            onPdfMetadataUpdate = { pdf, title, authors, projects, people, arxivId ->
                                viewModel.updateMetadata(pdf, title, authors, projects, people, arxivId)
                            },
                            knownAuthors = knownAuthors
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun SplitFab(onImport: () -> Unit, onNewNote: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = FloatingActionButtonDefaults.containerColor,
        shadowElevation = 6.dp,
        tonalElevation = 6.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(56.dp).clickable(onClick = onImport),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Description, contentDescription = "Import PDF")
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(36.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                modifier = Modifier.size(56.dp).clickable(onClick = onNewNote),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Create, contentDescription = "New note")
            }
        }
    }
}

private fun relativePath(pdfUri: Uri, rootUri: Uri): String {
    return try {
        val docId  = DocumentsContract.getDocumentId(pdfUri)
        val treeId = DocumentsContract.getTreeDocumentId(rootUri)
        if (docId.startsWith("$treeId/")) docId.removePrefix("$treeId/")
        else pdfUri.lastPathSegment ?: docId
    } catch (_: Exception) {
        pdfUri.lastPathSegment ?: pdfUri.toString()
    }
}

@Composable
private fun TypeFilterToggle(
    selected: TypeFilter,
    onSelect: (TypeFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf(TypeFilter.DOCUMENT to "Doc", TypeFilter.NOTE to "Note", TypeFilter.ALL to "All")
            .forEach { (filter, label) ->
                val isSelected = selected == filter
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) primary else onSurfaceVariant,
                    modifier = Modifier
                        .clickable { onSelect(filter) }
                        .drawBehind {
                            if (isSelected) {
                                drawLine(
                                    color = primary,
                                    start = Offset(0f, size.height),
                                    end = Offset(size.width, size.height),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                        }
                        .padding(bottom = 2.dp)
                )
            }
    }
}

private fun isDefaultNoteTitle(title: String) =
    title.matches(Regex("Note on .+ \\d{4}\\.\\d{2}\\.\\d{2} at \\d{2}:(00|30)"))

private fun formatNoteCreationDate(millis: Long): String? {
    if (millis == 0L) return null
    val cal = java.util.Calendar.getInstance().also {
        it.timeInMillis = millis
        it.set(java.util.Calendar.MINUTE, if (it.get(java.util.Calendar.MINUTE) >= 30) 30 else 0)
        it.set(java.util.Calendar.SECOND, 0)
        it.set(java.util.Calendar.MILLISECOND, 0)
    }
    val day = cal.getDisplayName(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.LONG, java.util.Locale.getDefault())
    return "$day %04d.%02d.%02d at %02d:%02d".format(
        cal.get(java.util.Calendar.YEAR),
        cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH),
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE)
    )
}

private fun highlightMatches(text: String, query: String, highlightColor: Color): AnnotatedString {
    if (query.isBlank()) return AnnotatedString(text)
    return buildAnnotatedString {
        val lower = text.lowercase()
        val q = query.lowercase()
        var start = 0
        while (true) {
            val idx = lower.indexOf(q, start)
            if (idx == -1) { append(text.substring(start)); break }
            append(text.substring(start, idx))
            withStyle(SpanStyle(background = highlightColor)) { append(text.substring(idx, idx + q.length)) }
            start = idx + q.length
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContentList(
    items: List<FileSystemItem>,
    rootUri: Uri,
    searchQuery: String = "",
    onPdfClick: (PdfFile) -> Unit,
    onPdfDelete: (PdfFile) -> Unit,
    onPdfMetadataUpdate: (PdfFile, String, List<String>, List<String>, List<String>, String) -> Unit,
    knownAuthors: List<String> = emptyList(),
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier
) {
    var menuTarget by remember { mutableStateOf<FileSystemItem?>(null) }
    var pendingDelete by remember { mutableStateOf<FileSystemItem.PdfItem?>(null) }
    var editTarget by remember { mutableStateOf<PdfFile?>(null) }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete?") },
            text = { Text("\"${item.pdf.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onPdfDelete(item.pdf)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    editTarget?.let { pdf ->
        EditMetadataDialog(
            title = pdf.title,
            authors = pdf.authors,
            people = pdf.people,
            isNote = pdf.type == PdfType.NOTE,
            arxivId = pdf.arxivId,
            createdAt = pdf.createdAt,
            fileUri = pdf.uri,
            rootUri = rootUri,
            knownAuthors = knownAuthors,
            onSave = { newTitle, newAuthors, newPeople, newArxivId ->
                onPdfMetadataUpdate(pdf, newTitle, newAuthors, pdf.projects, newPeople, newArxivId)
                editTarget = null
            },
            onDismiss = { editTarget = null }
        )
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(items, key = { item -> "pdf:${(item as FileSystemItem.PdfItem).pdf.uri}" }) { item ->
            val pdf = (item as FileSystemItem.PdfItem).pdf
            val title = pdf.title.takeIf { it.isNotBlank() } ?: relativePath(pdf.uri, rootUri)
            val authorsText = if (pdf.type == PdfType.NOTE) {
                pdf.people.joinToString(" \u2022 ").takeIf { it.isNotBlank() }
            } else {
                pdf.authors.joinToString(" \u2022 ").takeIf { it.isNotBlank() }
            }
            val filename = relativePath(pdf.uri, rootUri)
            val icon = if (pdf.type == PdfType.NOTE) Icons.Default.Create else Icons.Default.Description
            val q = searchQuery.trim().lowercase()
            val matchedPeople = if (q.isNotBlank() && pdf.type != PdfType.NOTE) pdf.people.filter { it.lowercase().contains(q) } else emptyList()
            val showArxiv = pdf.arxivId.isNotBlank() && (q.isBlank() || pdf.arxivId.lowercase().contains(q))
            val createdAtText = if (pdf.type == PdfType.NOTE && pdf.createdAt != 0L
                && !isDefaultNoteTitle(pdf.title.takeIf { it.isNotBlank() } ?: ""))
                formatNoteCreationDate(pdf.createdAt) else null
            val highlightColor = MaterialTheme.colorScheme.primaryContainer
            Box {
                ListItem(
                    leadingContent = {
                        Icon(imageVector = icon, contentDescription = null)
                    },
                    headlineContent = { Text(highlightMatches(title, q, highlightColor)) },
                    supportingContent = {
                        Column {
                            if (authorsText != null) {
                                Text(
                                    text = highlightMatches(authorsText, q, highlightColor),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (createdAtText != null) {
                                Text(
                                    text = createdAtText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (matchedPeople.isNotEmpty()) {
                                val peopleText = "People: " + matchedPeople.joinToString(" • ")
                                Text(
                                    text = highlightMatches(peopleText, q, highlightColor),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (showArxiv) {
                                Text(
                                    text = highlightMatches("arXiv: ${pdf.arxivId}", q, highlightColor),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = highlightMatches(filename, q, highlightColor),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Light
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPdfClick(pdf) },
                            onLongClick = { menuTarget = item }
                        )
                )
                DropdownMenu(
                    expanded = (menuTarget as? FileSystemItem.PdfItem)?.pdf?.uri == pdf.uri,
                    onDismissRequest = { menuTarget = null },
                    properties = PopupProperties(focusable = true)
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit metadata") },
                        onClick = { editTarget = pdf; menuTarget = null }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { pendingDelete = item; menuTarget = null }
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
