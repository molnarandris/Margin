package io.github.molnarandris.margin.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.molnarandris.margin.data.PdfFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPdf: (dirUri: Uri, docUri: Uri) -> Unit,
    viewModel: HomeViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    val directoryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onDirectorySelected(uri)
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
                    if (uiState is HomeUiState.Ready) {
                        IconButton(onClick = { directoryPicker.launch(null) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Change directory")
                        }
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
                    Button(onClick = { directoryPicker.launch(null) }) {
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
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfList(
    pdfs: List<PdfFile>,
    onPdfClick: (PdfFile) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(pdfs, key = { it.uri.toString() }) { pdf ->
            ListItem(
                headlineContent = { Text(pdf.name) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPdfClick(pdf) }
            )
            HorizontalDivider()
        }
    }
}
