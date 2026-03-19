package io.github.molnarandris.margin.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import io.github.molnarandris.margin.data.PdfFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPdf: (dirUri: Uri, docUri: Uri) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPdfs()
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
            TopAppBar(
                title = { Text("Margin") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            if (uiState is HomeUiState.Ready) {
                FloatingActionButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) {
                    Icon(Icons.Default.Add, contentDescription = "Import PDF")
                }
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
                if (state.pdfs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No PDFs yet. Tap + to import one.")
                    }
                } else {
                    PdfList(
                        pdfs = state.pdfs,
                        onPdfClick = { onOpenPdf(state.directoryUri, it.uri) },
                        onPdfDelete = { viewModel.deletePdf(it) },
                        onPdfMetadataUpdate = { pdf, title, author ->
                            viewModel.updateMetadata(pdf, title, author)
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfList(
    pdfs: List<PdfFile>,
    onPdfClick: (PdfFile) -> Unit,
    onPdfDelete: (PdfFile) -> Unit,
    onPdfMetadataUpdate: (PdfFile, String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuTarget by remember { mutableStateOf<PdfFile?>(null) }
    var pendingDelete by remember { mutableStateOf<PdfFile?>(null) }
    var editTarget by remember { mutableStateOf<PdfFile?>(null) }

    pendingDelete?.let { pdf ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete file?") },
            text = { Text("\"${pdf.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { onPdfDelete(pdf); pendingDelete = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    editTarget?.let { pdf ->
        var title by rememberSaveable(pdf.uri) { mutableStateOf(pdf.title) }
        var author by rememberSaveable(pdf.uri) { mutableStateOf(pdf.author) }
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
                    OutlinedTextField(
                        value = author,
                        onValueChange = { author = it },
                        label = { Text("Author") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onPdfMetadataUpdate(pdf, title, author); editTarget = null }) {
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
        items(pdfs, key = { it.uri.toString() }) { pdf ->
            val meta = listOfNotNull(
                pdf.title.takeIf { it.isNotBlank() },
                pdf.author.takeIf { it.isNotBlank() }
            ).joinToString(" — ")
            Box {
                ListItem(
                    headlineContent = { Text(pdf.name) },
                    supportingContent = if (meta.isNotBlank()) ({ Text(meta) }) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onPdfClick(pdf) },
                            onLongClick = { menuTarget = pdf }
                        )
                )
                DropdownMenu(
                    expanded = menuTarget?.uri == pdf.uri,
                    onDismissRequest = { menuTarget = null },
                    properties = PopupProperties(focusable = true)
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit metadata") },
                        onClick = { editTarget = pdf; menuTarget = null }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        onClick = { pendingDelete = pdf; menuTarget = null }
                    )
                }
            }
            HorizontalDivider()
        }
    }
}
