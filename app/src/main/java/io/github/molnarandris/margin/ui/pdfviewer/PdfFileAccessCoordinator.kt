package io.github.molnarandris.margin.ui.pdfviewer

import android.content.ContentResolver
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PdfFileAccessCoordinator(private val contentResolver: ContentResolver) {

    private val mutex = Mutex()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val pageCount: Int get() = renderer?.pageCount ?: 0

    // Called during initial load — no mutex needed, caller coordinates via fileWriteLockFor
    fun open(newPfd: ParcelFileDescriptor) {
        pfd?.close()
        renderer?.close()
        pfd = newPfd
        renderer = PdfRenderer(newPfd)
    }

    // Queues a close after any pending saves on saveScope complete, then cancels saveScope
    fun close() {
        saveScope.launch {
            mutex.withLock {
                renderer?.close()
                pfd?.close()
                renderer = null
                pfd = null
            }
            saveScope.cancel()
        }
    }

    // Acquires mutex, closes renderer, runs write block, reopens renderer, runs afterOpen with
    // the fresh renderer. Most callers leave afterOpen empty; pass it when post-write rendering
    // is needed inside the same lock scope (e.g. reloadPage after erasing ink or deleting a highlight).
    suspend fun withWrite(
        uri: Uri,
        afterOpen: suspend (PdfRenderer) -> Unit = {},
        write: suspend () -> Unit = {}
    ) {
        mutex.withLock {
            renderer?.close()
            pfd?.close()
            renderer = null
            pfd = null
            write()
            val newPfd = contentResolver.openFileDescriptor(uri, "r") ?: return@withLock
            pfd = newPfd
            val newRenderer = PdfRenderer(newPfd)
            renderer = newRenderer
            afterOpen(newRenderer)
        }
    }

    // Acquires mutex without closing the renderer — for read-only operations (page rendering).
    suspend fun withRenderer(block: suspend (PdfRenderer) -> Unit) {
        mutex.withLock {
            val r = renderer ?: return@withLock
            block(r)
        }
    }
}
