package io.github.molnarandris.margin.ui.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
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

    LaunchedEffect(Unit) {
        viewModel.openPdfEvent.collect { docUri ->
            val ready = viewModel.uiState.value as? HomeUiState.Ready ?: return@collect
            onOpenPdf(ready.rootUri, docUri)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshContents()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pdfPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importPdf(uri)
    }

    val readyState = uiState as? HomeUiState.Ready
    BackHandler(enabled = readyState != null && !readyState.isAtRoot) {
        viewModel.navigateUp()
    }

    var showCreateFolderDialog by remember { mutableStateOf(false) }

    if (showCreateFolderDialog) {
        CreateFolderDialog(
            onConfirm = { name ->
                viewModel.createDirectory(name)
                showCreateFolderDialog = false
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(readyState?.currentDirName ?: "Margin") },
                navigationIcon = {
                    if (readyState != null && !readyState.isAtRoot) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (readyState != null) {
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
                                    text = { Text("Last modified") },
                                    onClick = { viewModel.setSortOrder(SortOrder.BY_LAST_MODIFIED); showSortMenu = false },
                                    trailingIcon = if (sortOrder == SortOrder.BY_LAST_MODIFIED) {
                                        { Icon(Icons.Default.Check, contentDescription = null) }
                                    } else null
                                )
                            }
                        }
                        IconButton(onClick = { showCreateFolderDialog = true }) {
                            Icon(Icons.Default.CreateNewFolder, contentDescription = "Create folder")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
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
                            Text(if (state.activeAuthorFilter != null) "No PDFs by this author." else "No PDFs yet. Tap + to import one.")
                        }
                    } else {
                        ContentList(
                            items = state.items,
                            onDirClick = { viewModel.navigateInto(it.name) },
                            onDirDelete = { viewModel.deleteItem(it.uri) },
                            onPdfClick = { onOpenPdf(state.rootUri, it.uri) },
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
private fun CreateFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim()) },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ContentList(
    items: List<FileSystemItem>,
    onDirClick: (FileSystemItem.DirItem) -> Unit,
    onDirDelete: (FileSystemItem.DirItem) -> Unit,
    onPdfClick: (PdfFile) -> Unit,
    onPdfDelete: (PdfFile) -> Unit,
    onPdfMetadataUpdate: (PdfFile, String, List<String>, List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuTarget by remember { mutableStateOf<FileSystemItem?>(null) }
    var pendingDelete by remember { mutableStateOf<FileSystemItem?>(null) }
    var editTarget by remember { mutableStateOf<PdfFile?>(null) }

    pendingDelete?.let { item ->
        val name = when (item) {
            is FileSystemItem.DirItem -> item.name
            is FileSystemItem.PdfItem -> item.pdf.name
        }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete?") },
            text = { Text("\"$name\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    when (item) {
                        is FileSystemItem.DirItem -> onDirDelete(item)
                        is FileSystemItem.PdfItem -> onPdfDelete(item.pdf)
                    }
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
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(items, key = { item ->
            when (item) {
                is FileSystemItem.DirItem -> "dir:${item.uri}"
                is FileSystemItem.PdfItem -> "pdf:${item.pdf.uri}"
            }
        }) { item ->
            Box {
                when (item) {
                    is FileSystemItem.DirItem -> {
                        ListItem(
                            headlineContent = { Text(item.name) },
                            leadingContent = {
                                Icon(Icons.Default.Folder, contentDescription = null)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onDirClick(item) },
                                    onLongClick = { menuTarget = item }
                                )
                        )
                        DropdownMenu(
                            expanded = (menuTarget as? FileSystemItem.DirItem)?.uri == item.uri,
                            onDismissRequest = { menuTarget = null },
                            properties = PopupProperties(focusable = true)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { pendingDelete = item; menuTarget = null }
                            )
                        }
                    }
                    is FileSystemItem.PdfItem -> {
                        val pdf = item.pdf
                        val meta = listOfNotNull(
                            pdf.title.takeIf { it.isNotBlank() },
                            pdf.authors.joinToString(", ").takeIf { it.isNotBlank() }
                        ).joinToString(" — ")
                        ListItem(
                            headlineContent = { Text(pdf.name) },
                            supportingContent = if (meta.isNotBlank()) ({ Text(meta) }) else null,
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
                }
            }
            HorizontalDivider()
        }
    }
}
