package io.github.molnarandris.margin.ui.pdfviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class PdfViewerUiState {
    object Loading : PdfViewerUiState()
    data class Ready(val pages: List<Bitmap>) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
}

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Loading)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    fun loadPdf(dirUri: Uri, docId: String) {
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            val result = renderPages(dirUri, docId)
            _uiState.value = result
        }
    }

    private suspend fun renderPages(dirUri: Uri, docId: String): PdfViewerUiState = withContext(Dispatchers.IO) {
        try {
            val app = getApplication<Application>()
            val docUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
            val pfd = app.contentResolver.openFileDescriptor(docUri, "r")
                ?: return@withContext PdfViewerUiState.Error("Could not open file")

            pfd.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    val pages = (0 until renderer.pageCount).map { index ->
                        renderer.openPage(index).use { page ->
                            val scale = 2f
                            val bitmap = Bitmap.createBitmap(
                                (page.width * scale).toInt(),
                                (page.height * scale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bitmap
                        }
                    }
                    PdfViewerUiState.Ready(pages)
                }
            }
        } catch (e: Exception) {
            PdfViewerUiState.Error(e.message ?: "Failed to render PDF")
        }
    }
}
