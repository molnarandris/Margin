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

enum class SortOrder { BY_NAME, BY_LAST_MODIFIED, BY_LAST_OPENED }

private fun List<FileSystemItem>.applySortOrder(order: SortOrder): List<FileSystemItem> {
    val pdfs = filterIsInstance<FileSystemItem.PdfItem>()
    return when (order) {
        SortOrder.BY_NAME -> pdfs.sortedBy { it.pdf.name }
        SortOrder.BY_LAST_MODIFIED -> pdfs.sortedByDescending { it.pdf.lastModified }
        SortOrder.BY_LAST_OPENED -> pdfs.sortedByDescending { it.pdf.lastOpened }
    }
}

sealed class HomeUiState {
    object Loading : HomeUiState()
    object NoDirectory : HomeUiState()
    data class Ready(
        val rootUri: Uri,
        val items: List<FileSystemItem>,
        val allItems: List<FileSystemItem>
    ) : HomeUiState()
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

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        viewModelScope.launch {
            PdfRepository.pdfOpenedFlow.collect { (uri, timestamp) ->
                val state = _uiState.value as? HomeUiState.Ready ?: return@collect
                val allItems = state.allItems.map { item ->
                    if (item is FileSystemItem.PdfItem && item.pdf.uri == uri)
                        FileSystemItem.PdfItem(item.pdf.copy(lastOpened = timestamp))
                    else item
                }.applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = allItems)
            }
        }
        viewModelScope.launch {
            val saved = prefsRepo.getSortOrder()
            if (saved != null) _sortOrder.value = runCatching { SortOrder.valueOf(saved) }.getOrDefault(SortOrder.BY_NAME)
            prefsRepo.directoryUriString.collect { uriString ->
                if (uriString == null) {
                    _uiState.value = HomeUiState.NoDirectory
                } else {
                    val uri = Uri.parse(uriString)
                    val allItems = pdfRepo.getAllPdfs()
                        .map { FileSystemItem.PdfItem(it) }
                        .applySortOrder(_sortOrder.value)
                    _uiState.value = HomeUiState.Ready(uri, allItems, allItems)
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
        val allItems = state.allItems.applySortOrder(order)
        _uiState.value = state.copy(allItems = allItems, items = allItems)
        viewModelScope.launch { prefsRepo.saveSortOrder(order.name) }
    }

    fun importPdf(sourceUri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val success = pdfRepo.importPdf(sourceUri, state.rootUri, emptyList())
            if (success) {
                val allItems = pdfRepo.getAllPdfs()
                    .map { FileSystemItem.PdfItem(it) }
                    .applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = allItems)
            }
        }
    }

    fun deleteItem(uri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val deleted = pdfRepo.deletePdf(uri)
            if (deleted) {
                prefsRepo.clearPdfData(uri)
                val allItems = pdfRepo.getAllPdfs()
                    .map { FileSystemItem.PdfItem(it) }
                    .applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = allItems)
            }
        }
    }

    fun updateMetadata(pdf: PdfFile, title: String, authors: List<String>, projects: List<String>) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val updated = pdfRepo.updateMetadata(pdf.uri, title, authors, projects)
            if (updated) {
                val newPdf = pdf.copy(title = title, authors = authors, projects = projects)
                val newItem = FileSystemItem.PdfItem(newPdf)
                val allItems = state.allItems.map {
                    if (it is FileSystemItem.PdfItem && it.pdf.uri == pdf.uri) newItem else it
                }
                _uiState.value = state.copy(allItems = allItems, items = allItems)
            }
        }
    }

    fun createNote() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val uri = pdfRepo.createBlankPdf(state.rootUri) ?: return@launch
            _openPdfEvent.emit(uri)
            val allItems = pdfRepo.getAllPdfs()
                .map { FileSystemItem.PdfItem(it) }
                .applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = allItems)
        }
    }

    fun removeFromDatabase(uri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            pdfRepo.removeFromDatabase(uri)
            val allItems = pdfRepo.getAllPdfs()
                .map { FileSystemItem.PdfItem(it) }
                .applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = allItems)
        }
    }

    fun syncFilesystem() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        if (_isRefreshing.value) return
        viewModelScope.launch {
            _isRefreshing.value = true
            pdfRepo.syncWithFilesystem(state.rootUri)
            val allItems = pdfRepo.getAllPdfs()
                .map { FileSystemItem.PdfItem(it) }
                .applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = allItems)
            _isRefreshing.value = false
        }
    }

    fun refreshContents() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val allItems = pdfRepo.getAllPdfs()
                .map { FileSystemItem.PdfItem(it) }
                .applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = allItems)
        }
    }
}
