package io.github.molnarandris.margin.ui.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.molnarandris.margin.data.FileSystemItem
import io.github.molnarandris.margin.data.PdfFile
import io.github.molnarandris.margin.data.PdfRepository
import io.github.molnarandris.margin.data.PdfType
import io.github.molnarandris.margin.data.PreferencesRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SortOrder { BY_NAME, BY_RECENT }
enum class TypeFilter { ALL, DOCUMENT, NOTE }

private fun List<FileSystemItem>.applySortOrder(order: SortOrder): List<FileSystemItem> {
    val pdfs = filterIsInstance<FileSystemItem.PdfItem>()
    return when (order) {
        SortOrder.BY_NAME -> pdfs.sortedBy { it.pdf.name }
        SortOrder.BY_RECENT -> pdfs.sortedByDescending { maxOf(it.pdf.lastModified, it.pdf.lastOpened) }
    }
}

private fun List<FileSystemItem>.applyTypeFilter(filter: TypeFilter): List<FileSystemItem> {
    if (filter == TypeFilter.ALL) return this
    val targetType = if (filter == TypeFilter.DOCUMENT) PdfType.DOCUMENT else PdfType.NOTE
    return filterIsInstance<FileSystemItem.PdfItem>().filter { it.pdf.type == targetType }
}

private fun List<FileSystemItem>.applySearch(query: String): List<FileSystemItem> {
    if (query.isBlank()) return this
    val q = query.trim().lowercase()
    return filterIsInstance<FileSystemItem.PdfItem>().filter { item ->
        item.pdf.name.lowercase().contains(q) ||
        item.pdf.title.lowercase().contains(q) ||
        item.pdf.authors.any { it.lowercase().contains(q) }
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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _typeFilter = MutableStateFlow(TypeFilter.ALL)
    val typeFilter: StateFlow<TypeFilter> = _typeFilter.asStateFlow()

    init {
        viewModelScope.launch {
            PdfRepository.pdfOpenedFlow.collect { (uri, timestamp) ->
                val state = _uiState.value as? HomeUiState.Ready ?: return@collect
                val allItems = state.allItems.map { item ->
                    if (item is FileSystemItem.PdfItem && item.pdf.uri == uri)
                        FileSystemItem.PdfItem(item.pdf.copy(lastOpened = timestamp))
                    else item
                }.applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
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
                    _uiState.value = HomeUiState.Ready(uri, allItems, allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
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

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        val state = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = state.copy(items = state.allItems.applyTypeFilter(_typeFilter.value).applySearch(query))
    }

    fun setTypeFilter(filter: TypeFilter) {
        _typeFilter.value = filter
        val state = _uiState.value as? HomeUiState.Ready ?: return
        _uiState.value = state.copy(
            items = state.allItems.applyTypeFilter(filter).applySearch(_searchQuery.value)
        )
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        val state = _uiState.value as? HomeUiState.Ready ?: return
        val allItems = state.allItems.applySortOrder(order)
        _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
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
                _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
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
                _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
            }
        }
    }

    fun updateMetadata(pdf: PdfFile, title: String, authors: List<String>, projects: List<String>) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val updated = pdfRepo.updateMetadata(pdf.uri, title, authors, projects)
            if (updated) {
                val newPdf = pdf.copy(title = title, authors = authors, projects = projects, lastOpened = System.currentTimeMillis())
                val newItem = FileSystemItem.PdfItem(newPdf)
                val allItems = state.allItems.map {
                    if (it is FileSystemItem.PdfItem && it.pdf.uri == pdf.uri) newItem else it
                }.applySortOrder(_sortOrder.value)
                _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
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
            _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
        }
    }

    fun removeFromDatabase(uri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            pdfRepo.removeFromDatabase(uri)
            val allItems = pdfRepo.getAllPdfs()
                .map { FileSystemItem.PdfItem(it) }
                .applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
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
            _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
            _isRefreshing.value = false
        }
    }

    fun refreshContents() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val allItems = pdfRepo.getAllPdfs()
                .map { FileSystemItem.PdfItem(it) }
                .applySortOrder(_sortOrder.value)
            _uiState.value = state.copy(allItems = allItems, items = allItems.applyTypeFilter(_typeFilter.value).applySearch(_searchQuery.value))
        }
    }
}
