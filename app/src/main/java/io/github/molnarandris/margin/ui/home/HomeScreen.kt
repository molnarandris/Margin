package io.github.molnarandris.margin.ui.home

import android.net.Uri
import android.provider.DocumentsContract
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.InputChip
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
import androidx.compose.ui.Alignment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.molnarandris.margin.data.FileSystemItem
import io.github.molnarandris.margin.data.PdfFile

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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var isSearchActive by remember { mutableStateOf(searchQuery.isNotBlank()) }
    var fileNotFoundPdf by remember { mutableStateOf<PdfFile?>(null) }

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
                            modifier = Modifier.fillMaxWidth()
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
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                )
                            )
                            if (uiState is HomeUiState.Ready) {
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
                            onPdfMetadataUpdate = { pdf, title, authors, projects ->
                                viewModel.updateMetadata(pdf, title, authors, projects)
                            }
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
                Icon(Icons.Default.Add, contentDescription = "Import PDF")
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
                Icon(Icons.Default.Description, contentDescription = "New note")
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ContentList(
    items: List<FileSystemItem>,
    rootUri: Uri,
    onPdfClick: (PdfFile) -> Unit,
    onPdfDelete: (PdfFile) -> Unit,
    onPdfMetadataUpdate: (PdfFile, String, List<String>, List<String>) -> Unit,
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
        var title by rememberSaveable(pdf.uri) { mutableStateOf(pdf.title) }
        var authorChips by remember(pdf.uri) { mutableStateOf(pdf.authors) }
        var newAuthorText by rememberSaveable(pdf.uri) { mutableStateOf("") }
        var projectChips by remember(pdf.uri) { mutableStateOf(pdf.projects) }
        var newProjectText by rememberSaveable(pdf.uri) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text("Edit metadata") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        singleLine = true
                    )
                    if (authorChips.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            authorChips.forEach { author ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(author) },
                                    trailingIcon = {
                                        IconButton(onClick = { authorChips = authorChips - author }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newAuthorText,
                            onValueChange = { newAuthorText = it },
                            label = { Text("Add author") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val trimmed = newAuthorText.trim()
                            if (trimmed.isNotEmpty() && trimmed !in authorChips) {
                                authorChips = authorChips + trimmed
                            }
                            newAuthorText = ""
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add author")
                        }
                    }
                    if (projectChips.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            projectChips.forEach { project ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text(project) },
                                    trailingIcon = {
                                        IconButton(onClick = { projectChips = projectChips - project }) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove")
                                        }
                                    }
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newProjectText,
                            onValueChange = { newProjectText = it },
                            label = { Text("Add project") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val trimmed = newProjectText.trim()
                            if (trimmed.isNotEmpty() && trimmed !in projectChips) {
                                projectChips = projectChips + trimmed
                            }
                            newProjectText = ""
                        }) {
                            Icon(Icons.Default.Add, contentDescription = "Add project")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onPdfMetadataUpdate(pdf, title, authorChips, projectChips); editTarget = null }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) { Text("Cancel") }
            }
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
            val authorsText = pdf.authors.joinToString(" \u2022 ").takeIf { it.isNotBlank() }
            val filename = relativePath(pdf.uri, rootUri)
            Box {
                ListItem(
                    headlineContent = { Text(title) },
                    supportingContent = {
                        Column {
                            if (authorsText != null) {
                                Text(
                                    text = authorsText,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Text(
                                text = filename,
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
