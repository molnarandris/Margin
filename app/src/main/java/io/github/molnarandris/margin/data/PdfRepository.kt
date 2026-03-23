package io.github.molnarandris.margin.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class PdfFile(val uri: Uri, val name: String, val title: String = "", val author: String = "")

private fun backupFileFor(context: Context, uri: Uri): File =
    File(context.filesDir, "backup_${uri.toString().hashCode()}.pdf")

private fun PDDocument.saveWithBackup(context: Context, uri: Uri) {
    val backup = backupFileFor(context, uri)
    context.contentResolver.openInputStream(uri)?.use { input ->
        backup.outputStream().use { input.copyTo(it) }
    }
    context.contentResolver.openOutputStream(uri, "wt")!!.use { save(it) }
    backup.delete()
}

class PdfRepository(private val context: Context) {

    suspend fun listPdfs(directoryUri: Uri): List<PdfFile> = withContext(Dispatchers.IO) {
        val dir = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext emptyList()
        dir.listFiles()
            .filter { it.isFile && it.type == "application/pdf" }
            .map { file ->
                val name = file.name ?: "Untitled.pdf"
                val (title, author) = try {
                    context.contentResolver.openInputStream(file.uri)?.use { stream ->
                        val doc = PDDocument.load(stream)
                        val info = doc.documentInformation
                        val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                        val a = info?.author?.takeIf { it.isNotBlank() } ?: ""
                        doc.close()
                        t to a
                    } ?: ("" to "")
                } catch (e: Exception) {
                    "" to ""
                }
                PdfFile(uri = file.uri, name = name, title = title, author = author)
            }
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

    suspend fun deletePdf(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        DocumentFile.fromSingleUri(context, uri)?.delete() ?: false
    }

    suspend fun updateMetadata(uri: Uri, title: String, author: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                    ?: return@withContext false
                val info = doc.documentInformation
                info.title = title.ifBlank { null }
                info.author = author.ifBlank { null }
                doc.saveWithBackup(context, uri)
                doc.close()
                true
            } catch (e: Exception) {
                false
            }
        }

    suspend fun createBlankPdf(directoryUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext null
            val destFile = dir.createFile("application/pdf", "Note") ?: return@withContext null
            val doc = PDDocument()
            doc.addPage(PDPage(PDRectangle.A4))
            context.contentResolver.openOutputStream(destFile.uri)?.use { doc.save(it) }
            doc.close()
            destFile.uri
        } catch (e: Exception) {
            null
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
