package io.github.molnarandris.margin.ui.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.molnarandris.margin.data.FileSystemItem
import io.github.molnarandris.margin.data.PdfFile
import io.github.molnarandris.margin.data.PdfRepository
import io.github.molnarandris.margin.data.PreferencesRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SortOrder { BY_NAME, BY_LAST_MODIFIED }

private fun List<FileSystemItem>.applySortOrder(order: SortOrder): List<FileSystemItem> {
    val dirs = filterIsInstance<FileSystemItem.DirItem>()
    val pdfs = filterIsInstance<FileSystemItem.PdfItem>()
    return when (order) {
        SortOrder.BY_NAME -> dirs.sortedBy { it.name } + pdfs.sortedBy { it.pdf.name }
        SortOrder.BY_LAST_MODIFIED -> dirs.sortedByDescending { it.lastModified } + pdfs.sortedByDescending { it.pdf.lastModified }
    }
}

sealed class HomeUiState {
    object Loading : HomeUiState()
    object NoDirectory : HomeUiState()
    data class Ready(
        val rootUri: Uri,
        val currentPath: List<String>,
        val items: List<FileSystemItem>
    ) : HomeUiState() {
        val isAtRoot get() = currentPath.isEmpty()
        val currentDirName get() = currentPath.lastOrNull() ?: "Margin"
    }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = PreferencesRepository(application)
    private val pdfRepo = PdfRepository(application)

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _openPdfEvent = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val openPdfEvent: SharedFlow<Uri> = _openPdfEvent.asSharedFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.BY_NAME)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = prefsRepo.getSortOrder()
            if (saved != null) _sortOrder.value = runCatching { SortOrder.valueOf(saved) }.getOrDefault(SortOrder.BY_NAME)
            prefsRepo.directoryUriString.collect { uriString ->
                if (uriString == null) {
                    _uiState.value = HomeUiState.NoDirectory
                } else {
                    val uri = Uri.parse(uriString)
                    val items = pdfRepo.listContents(uri, emptyList()).applySortOrder(_sortOrder.value)
                    _uiState.value = HomeUiState.Ready(uri, emptyList(), items)
                }
            }
        }
    }

    fun onDirectorySelected(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        viewModelScope.launch {
            prefsRepo.saveDirectoryUri(uri.toString())
        }
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        val state = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = state.copy(items = state.items.applySortOrder(order))
        viewModelScope.launch { prefsRepo.saveSortOrder(order.name) }
    }

    fun importPdf(sourceUri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val success = pdfRepo.importPdf(sourceUri, state.rootUri, state.currentPath)
            if (success) {
                val items = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(items = items)
            }
        }
    }

    fun deleteItem(uri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val deleted = pdfRepo.deletePdf(uri)
            if (deleted) {
                prefsRepo.clearPdfData(uri)
                val items = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(items = items)
            }
        }
    }

    fun updateMetadata(pdf: PdfFile, title: String, author: String) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val updated = pdfRepo.updateMetadata(pdf.uri, title, author)
            if (updated) {
                val newItem = FileSystemItem.PdfItem(pdf.copy(title = title, author = author))
                _uiState.value = state.copy(items = state.items.map {
                    if (it is FileSystemItem.PdfItem && it.pdf.uri == pdf.uri) newItem else it
                })
            }
        }
    }

    fun createNote() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val uri = pdfRepo.createBlankPdf(state.rootUri) ?: return@launch
            _openPdfEvent.emit(uri)
            val items = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(items = items)
        }
    }

    fun refreshContents() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val items = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(items = items)
        }
    }

    fun navigateInto(dirName: String) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val newPath = state.currentPath + dirName
            val items = pdfRepo.listContents(state.rootUri, newPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(currentPath = newPath, items = items)
        }
    }

    fun navigateUp() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        if (state.isAtRoot) return
        viewModelScope.launch {
            val newPath = state.currentPath.dropLast(1)
            val items = pdfRepo.listContents(state.rootUri, newPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(currentPath = newPath, items = items)
        }
    }

    fun createDirectory(name: String) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val success = pdfRepo.createDirectory(state.rootUri, state.currentPath, name)
            if (success) {
                val items = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(items = items)
            }
        }
    }
}
