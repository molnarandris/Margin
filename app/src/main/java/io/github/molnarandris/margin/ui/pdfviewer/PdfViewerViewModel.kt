package io.github.molnarandris.margin.ui.pdfviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class PdfViewerUiState {
    object Loading : PdfViewerUiState()
    data class Ready(val pages: List<Bitmap>) : PdfViewerUiState()
    data class Error(val message: String) : PdfViewerUiState()
}

class PdfViewerViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<PdfViewerUiState>(PdfViewerUiState.Loading)
    val uiState: StateFlow<PdfViewerUiState> = _uiState.asStateFlow()

    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val renderMutex = Mutex()
    private var rerenderJob: Job? = null
    private var currentRenderScale = 2f

    fun loadPdf(dirUri: Uri, docId: String) {
        rerenderJob?.cancel()
        viewModelScope.launch {
            _uiState.value = PdfViewerUiState.Loading
            _uiState.value = renderPages(dirUri, docId)
        }
    }

    fun updateRenderScale(displayScale: Float, visibleIndices: List<Int>) {
        val targetScale = (displayScale * 2f).coerceIn(2f, 8f)
        if (kotlin.math.abs(targetScale - currentRenderScale) < 0.5f) return
        rerenderJob?.cancel()
        rerenderJob = viewModelScope.launch {
            val state = _uiState.value as? PdfViewerUiState.Ready ?: return@launch
            val newPages = state.pages.toMutableList()
            renderMutex.withLock {
                withContext(Dispatchers.IO) {
                    val r = renderer ?: return@withContext
                    currentRenderScale = targetScale
                    for (index in visibleIndices) {
                        if (index !in newPages.indices) continue
                        r.openPage(index).use { page ->
                            val bitmap = Bitmap.createBitmap(
                                (page.width * targetScale).toInt(),
                                (page.height * targetScale).toInt(),
                                Bitmap.Config.ARGB_8888
                            )
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            newPages[index] = bitmap
                        }
                    }
                }
            }
            _uiState.value = PdfViewerUiState.Ready(newPages)
        }
    }

    private suspend fun renderPages(dirUri: Uri, docId: String): PdfViewerUiState =
        withContext(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val docUri = DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)
                val newPfd = app.contentResolver.openFileDescriptor(docUri, "r")
                    ?: return@withContext PdfViewerUiState.Error("Could not open file")

                pfd?.close()
                renderer?.close()
                pfd = newPfd
                val newRenderer = PdfRenderer(newPfd)
                renderer = newRenderer
                currentRenderScale = 2f

                renderMutex.withLock {
                    val pages = (0 until newRenderer.pageCount).map { index ->
                        newRenderer.openPage(index).use { page ->
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
            } catch (e: Exception) {
                PdfViewerUiState.Error(e.message ?: "Failed to render PDF")
            }
        }

    override fun onCleared() {
        super.onCleared()
        renderer?.close()
        pfd?.close()
    }
}
