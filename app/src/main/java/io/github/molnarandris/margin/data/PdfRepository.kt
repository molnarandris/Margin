package io.github.molnarandris.margin.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PdfFile(val uri: Uri, val name: String)

class PdfRepository(private val context: Context) {

    fun listPdfs(directoryUri: Uri): List<PdfFile> {
        val dir = DocumentFile.fromTreeUri(context, directoryUri) ?: return emptyList()
        return dir.listFiles()
            .filter { it.isFile && it.type == "application/pdf" }
            .map { PdfFile(uri = it.uri, name = it.name ?: "Untitled.pdf") }
            .sortedBy { it.name }
    }

    suspend fun importPdf(sourceUri: Uri, directoryUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext false
            val name = resolveFileName(sourceUri)
            val destFile = dir.createFile("application/pdf", name) ?: return@withContext false
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun resolveFileName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
        } ?: "document.pdf"
    }
}
