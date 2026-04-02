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
import java.time.LocalDateTime
import java.util.Calendar

data class PdfFile(val uri: Uri, val name: String, val title: String = "", val author: String = "", val lastModified: Long = 0L)

sealed class FileSystemItem {
    data class PdfItem(val pdf: PdfFile) : FileSystemItem()
    data class DirItem(val uri: Uri, val name: String, val lastModified: Long = 0L) : FileSystemItem()
}

private fun backupFileFor(context: Context, uri: Uri): File =
    File(context.filesDir, "backup_${uri.toString().hashCode()}.pdf")

private fun PDDocument.saveWithBackup(context: Context, uri: Uri) {
    val backup = backupFileFor(context, uri)
    context.contentResolver.openInputStream(uri)?.use { input ->
        backup.outputStream().use { input.copyTo(it) }
    }
    documentInformation.setModificationDate(Calendar.getInstance())
    context.contentResolver.openOutputStream(uri, "wt")!!.use { save(it) }
    backup.delete()
}

class PdfRepository(private val context: Context) {

    private val dao = PdfDatabase.getInstance(context).pdfMetadataDao()

    private fun navigateToDir(rootUri: Uri, pathFromRoot: List<String>): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (segment in pathFromRoot) {
            dir = dir.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return dir
    }

    private fun navigateToDirOrCreate(rootUri: Uri, pathFromRoot: List<String>): DocumentFile? {
        var dir = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        for (segment in pathFromRoot) {
            dir = dir.findFile(segment)?.takeIf { it.isDirectory }
                ?: dir.createDirectory(segment)
                ?: return null
        }
        return dir
    }

    suspend fun listContents(rootUri: Uri, pathFromRoot: List<String>): List<FileSystemItem> = withContext(Dispatchers.IO) {
        val dir = navigateToDir(rootUri, pathFromRoot) ?: return@withContext emptyList()
        val dirs = mutableListOf<FileSystemItem.DirItem>()
        val pdfs = mutableListOf<FileSystemItem.PdfItem>()
        for (file in dir.listFiles()) {
            when {
                file.isDirectory -> dirs.add(FileSystemItem.DirItem(file.uri, file.name ?: "Untitled", file.lastModified()))
                file.isFile && file.type == "application/pdf" -> {
                    val name = file.name ?: "Untitled.pdf"
                    val uriStr = file.uri.toString()
                    val lastModified = file.lastModified()
                    val cached = dao.getByUri(uriStr)
                    val (title, author) = if (cached != null && cached.lastModified == lastModified) {
                        cached.title to cached.author
                    } else {
                        val meta = try {
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
                        dao.upsert(PdfMetadataEntity(uriStr, name, meta.first, meta.second, lastModified))
                        meta
                    }
                    pdfs.add(FileSystemItem.PdfItem(PdfFile(uri = file.uri, name = name, title = title, author = author, lastModified = lastModified)))
                }
            }
        }
        dirs + pdfs
    }

    suspend fun createDirectory(rootUri: Uri, pathFromRoot: List<String>, name: String): Boolean = withContext(Dispatchers.IO) {
        val dir = navigateToDir(rootUri, pathFromRoot) ?: return@withContext false
        dir.createDirectory(name) != null
    }

    suspend fun importPdf(sourceUri: Uri, rootUri: Uri, pathFromRoot: List<String>): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = navigateToDir(rootUri, pathFromRoot) ?: return@withContext false
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
        val deleted = DocumentFile.fromSingleUri(context, uri)?.delete() ?: false
        if (deleted) dao.deleteByUri(uri.toString())
        deleted
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
                val uriStr = uri.toString()
                val lastModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
                val existingName = dao.getByUri(uriStr)?.name ?: ""
                dao.upsert(PdfMetadataEntity(uriStr, existingName, title.ifBlank { "" }, author.ifBlank { "" }, lastModified))
                true
            } catch (e: Exception) {
                false
            }
        }

    suspend fun createBlankPdf(rootUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val now = LocalDateTime.now()
            val yy = "%02d".format(now.year % 100)
            val mm = "%02d".format(now.monthValue)
            val dd = "%02d".format(now.dayOfMonth)
            val hh = "%02d".format(now.hour)
            val roundedMin = if (now.minute >= 30) "30" else "00"
            val name = "$yy-$mm-$dd $hh:$roundedMin"
            val yyyy = "%04d".format(now.year)
            val dir = navigateToDirOrCreate(rootUri, listOf("Notes", yyyy, mm)) ?: return@withContext null
            val destFile = dir.createFile("application/pdf", name) ?: return@withContext null
            val doc = PDDocument()
            val info = doc.documentInformation
            info.creator = "Margin"
            info.setCreationDate(Calendar.getInstance())
            doc.documentInformation = info
            doc.addPage(PDPage(PDRectangle.A4))
            context.contentResolver.openOutputStream(destFile.uri)?.use { doc.save(it) }
            doc.close()
            val lastModified = destFile.lastModified()
            dao.upsert(PdfMetadataEntity(destFile.uri.toString(), name, "", "", lastModified))
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
