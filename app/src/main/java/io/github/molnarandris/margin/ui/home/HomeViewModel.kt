package io.github.molnarandris.margin.ui.home

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

sealed class HomeUiState {
    object Loading : HomeUiState()
    object NoDirectory : HomeUiState()
    data class Ready(val directoryUri: Uri, val pdfs: List<PdfFile>) : HomeUiState()
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsRepo = PreferencesRepository(application)
    private val pdfRepo = PdfRepository(application)

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Loading)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _openPdfEvent = MutableSharedFlow<Uri>(extraBufferCapacity = 1)
    val openPdfEvent: SharedFlow<Uri> = _openPdfEvent.asSharedFlow()

    init {
        viewModelScope.launch {
            prefsRepo.directoryUriString.collect { uriString ->
                if (uriString == null) {
                    _uiState.value = HomeUiState.NoDirectory
                } else {
                    val uri = Uri.parse(uriString)
                    val pdfs = pdfRepo.listPdfs(uri)
                    _uiState.value = HomeUiState.Ready(uri, pdfs)
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

    fun importPdf(sourceUri: Uri) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val success = pdfRepo.importPdf(sourceUri, state.directoryUri)
            if (success) {
                val pdfs = pdfRepo.listPdfs(state.directoryUri)
                _uiState.value = state.copy(pdfs = pdfs)
            }
        }
    }

    fun deletePdf(pdf: PdfFile) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val deleted = pdfRepo.deletePdf(pdf.uri)
            if (deleted) {
                _uiState.value = state.copy(pdfs = state.pdfs - pdf)
            }
        }
    }

    fun updateMetadata(pdf: PdfFile, title: String, author: String) {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val updated = pdfRepo.updateMetadata(pdf.uri, title, author)
            if (updated) {
                val newPdf = pdf.copy(title = title, author = author)
                _uiState.value = state.copy(pdfs = state.pdfs.map { if (it.uri == pdf.uri) newPdf else it })
            }
        }
    }

    fun createNote() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val uri = pdfRepo.createBlankPdf(state.directoryUri) ?: return@launch
            val pdfs = pdfRepo.listPdfs(state.directoryUri)
            _uiState.value = state.copy(pdfs = pdfs)
            _openPdfEvent.emit(uri)
        }
    }

    fun refreshPdfs() {
        val state = _uiState.value as? HomeUiState.Ready ?: return
        viewModelScope.launch {
            val pdfs = pdfRepo.listPdfs(state.directoryUri)
            _uiState.value = state.copy(pdfs = pdfs)
        }
    }
}
