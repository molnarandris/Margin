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
        val items: List<FileSystemItem>,     // filtered + sorted, shown in UI
        val allItems: List<FileSystemItem>,  // sorted but unfiltered, source of truth
        val activeAuthorFilter: String? = null
    ) : HomeUiState() {
        val isAtRoot get() = currentPath.isEmpty()
        val currentDirName get() = currentPath.lastOrNull() ?: "Margin"
        val availableAuthors: List<String> get() =
            allItems.filterIsInstance<FileSystemItem.PdfItem>()
                .flatMap { it.pdf.authors }
                .distinct()
                .sorted()
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

    private val _authorFilter = MutableStateFlow<String?>(null)
    val authorFilter: StateFlow<String?> = _authorFilter.asStateFlow()

    private fun applyFilters(items: List<FileSystemItem>): List<FileSystemItem> {
        val filter = _authorFilter.value ?: return items
        return items.filter { item ->
            item is FileSystemItem.DirItem ||
            (item is FileSystemItem.PdfItem && filter in item.pdf.authors)
        }
    }

    init {
        viewModelScope.launch {
            val saved = prefsRepo.getSortOrder()
            if (saved != null) _sortOrder.value = runCatching { SortOrder.valueOf(saved) }.getOrDefault(SortOrder.BY_NAME)
            prefsRepo.directoryUriString.collect { uriString ->
                if (uriString == null) {
                    _uiState.value = HomeUiState.NoDirectory
                } else {
                    val uri = Uri.parse(uriString)
                    val allItems = pdfRepo.listContents(uri, emptyList()).applySortOrder(_sortOrder.value)
                    _uiState.value = HomeUiState.Ready(uri, emptyList(), applyFilters(allItems), allItems)
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
        _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
        viewModelScope.launch { prefsRepo.saveSortOrder(order.name) }
    }

    fun setAuthorFilter(author: String?) {
        _authorFilter.value = author
        val state = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = state.copy(items = applyFilters(state.allItems), activeAuthorFilter = author)
    }

    fun importPdf(sourceUri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val success = pdfRepo.importPdf(sourceUri, state.rootUri, state.currentPath)
            if (success) {
                val allItems = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
            }
        }
    }

    fun deleteItem(uri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val deleted = pdfRepo.deletePdf(uri)
            if (deleted) {
                prefsRepo.clearPdfData(uri)
                val allItems = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
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
                _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
            }
        }
    }

    fun createNote() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val uri = pdfRepo.createBlankPdf(state.rootUri) ?: return@launch
            _openPdfEvent.emit(uri)
            val allItems = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
        }
    }

    fun refreshContents() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val allItems = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
        }
    }

    fun navigateInto(dirName: String) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val newPath = state.currentPath + dirName
            val allItems = pdfRepo.listContents(state.rootUri, newPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(currentPath = newPath, allItems = allItems, items = applyFilters(allItems))
        }
    }

    fun navigateUp() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        if (state.isAtRoot) return
        viewModelScope.launch {
            val newPath = state.currentPath.dropLast(1)
            val allItems = pdfRepo.listContents(state.rootUri, newPath).applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(currentPath = newPath, allItems = allItems, items = applyFilters(allItems))
        }
    }

    fun createDirectory(name: String) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val success = pdfRepo.createDirectory(state.rootUri, state.currentPath, name)
            if (success) {
                val allItems = pdfRepo.listContents(state.rootUri, state.currentPath).applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = applyFilters(allItems))
            }
        }
    }
}
