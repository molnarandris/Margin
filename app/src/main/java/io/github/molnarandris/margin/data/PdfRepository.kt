package io.github.molnarandris.margin.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

data class PdfFile(val uri: Uri, val name: String, val title: String = "", val authors: List<String> = emptyList(), val lastModified: Long = 0L, val type: PdfType = PdfType.DOCUMENT, val projects: List<String> = emptyList(), val lastOpened: Long = 0L)

sealed class FileSystemItem {
    data class PdfItem(val pdf: PdfFile) : FileSystemItem()
    data class DirItem(val uri: Uri, val name: String, val lastModified: Long = 0L) : FileSystemItem()
}

class PdfRepository(private val context: Context) {

    fun backupFileFor(uri: Uri): File =
        File(context.filesDir, "backup_${uri.toString().hashCode()}.pdf")

    suspend fun save(doc: PDDocument, uri: Uri) {
        fileWriteLockFor(uri).withLock {
            val backup = backupFileFor(uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use { input.copyTo(it) }
            }
            doc.documentInformation.setModificationDate(Calendar.getInstance())
            context.contentResolver.openOutputStream(uri, "wt")!!.use { doc.save(it) }
            backup.delete()
        }
    }

    companion object {
        private val fileWriteLocks = ConcurrentHashMap<String, Mutex>()
        fun fileWriteLockFor(uri: Uri): Mutex =
            fileWriteLocks.getOrPut(uri.toString()) { Mutex() }

        private val _pdfOpenedFlow = MutableSharedFlow<Pair<Uri, Long>>(extraBufferCapacity = 1)
        val pdfOpenedFlow: SharedFlow<Pair<Uri, Long>> = _pdfOpenedFlow

        private var _currentDocId: String? = null
        private val _previousDocParams = MutableStateFlow<Pair<Uri, String>?>(null)
        val previousDocParams: StateFlow<Pair<Uri, String>?> = _previousDocParams

        private const val MARGIN_NS = "http://github.com/molnarandris/margin/xmp/1.0/"
        private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

        fun readProjectsFromXmp(doc: PDDocument): List<String> {
            val metaStream = doc.documentCatalog.metadata ?: return emptyList()
            return try {
                val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                val xmlDoc = factory.newDocumentBuilder().parse(metaStream.exportXMPMetadata())
                val seqNodes = xmlDoc.getElementsByTagNameNS(MARGIN_NS, "Projects")
                if (seqNodes.length == 0) return emptyList()
                val lis = (seqNodes.item(0) as Element).getElementsByTagNameNS(RDF_NS, "li")
                (0 until lis.length).map { lis.item(it).textContent.trim() }.filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun writeProjectsToXmp(doc: PDDocument, projects: List<String>) {
            val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
            val builder = factory.newDocumentBuilder()

            val existingStream = doc.documentCatalog.metadata
            val parsed: org.w3c.dom.Document? = if (existingStream != null) {
                try { builder.parse(existingStream.exportXMPMetadata()) } catch (e: Exception) { null }
            } else null
            val xmpDoc: org.w3c.dom.Document = parsed ?: run {
                val d = builder.newDocument()
                val xmpmeta = d.createElementNS("adobe:ns:meta/", "x:xmpmeta")
                xmpmeta.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:x", "adobe:ns:meta/")
                d.appendChild(xmpmeta)
                val rdf = d.createElementNS(RDF_NS, "rdf:RDF")
                rdf.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:rdf", RDF_NS)
                xmpmeta.appendChild(rdf)
                d
            }

            // Remove any existing margin:Projects elements
            val existing = xmpDoc.getElementsByTagNameNS(MARGIN_NS, "Projects")
            repeat(existing.length) { existing.item(0).parentNode.removeChild(existing.item(0)) }

            if (projects.isNotEmpty()) {
                // Find the rdf:RDF element, creating it if missing
                val rdfNodes = xmpDoc.getElementsByTagNameNS(RDF_NS, "RDF")
                val rdf: Element = if (rdfNodes.length > 0) rdfNodes.item(0) as Element else {
                    val r = xmpDoc.createElementNS(RDF_NS, "rdf:RDF")
                    r.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:rdf", RDF_NS)
                    xmpDoc.documentElement.appendChild(r)
                    r
                }

                // Find or create a rdf:Description that carries the margin namespace
                var desc: Element? = null
                val descs = xmpDoc.getElementsByTagNameNS(RDF_NS, "Description")
                for (i in 0 until descs.length) {
                    val d = descs.item(i) as Element
                    if (d.getAttribute("xmlns:margin") == MARGIN_NS) { desc = d; break }
                }
                if (desc == null) {
                    desc = xmpDoc.createElementNS(RDF_NS, "rdf:Description")
                    desc.setAttributeNS(RDF_NS, "rdf:about", "")
                    desc.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:margin", MARGIN_NS)
                    rdf.appendChild(desc)
                }

                val projectsProp = xmpDoc.createElementNS(MARGIN_NS, "margin:Projects")
                val seq = xmpDoc.createElementNS(RDF_NS, "rdf:Seq")
                for (project in projects) {
                    val li = xmpDoc.createElementNS(RDF_NS, "rdf:li")
                    li.textContent = project
                    seq.appendChild(li)
                }
                projectsProp.appendChild(seq)
                desc.appendChild(projectsProp)
            }

            val bytes = ByteArrayOutputStream().also { out ->
                TransformerFactory.newInstance().newTransformer()
                    .transform(DOMSource(xmpDoc), StreamResult(out))
            }.toByteArray()

            doc.documentCatalog.metadata = PDMetadata(doc, ByteArrayInputStream(bytes))
        }
    }

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
        for (file in dir.listFiles().filter { it.name?.startsWith(".") != true }) {
            when {
                file.isDirectory -> dirs.add(FileSystemItem.DirItem(file.uri, file.name ?: "Untitled", file.lastModified()))
                file.isFile && file.type == "application/pdf" -> {
                    val name = file.name ?: "Untitled.pdf"
                    val uriStr = file.uri.toString()
                    val lastModified = file.lastModified()
                    val cached = dao.getByUri(uriStr)
                    val (title, authors, type, projects) = if (cached != null && cached.lastModified == lastModified) {
                        Quad(cached.title, cached.author.split(";").map { it.trim() }.filter { it.isNotBlank() }, cached.type, cached.projects.split(",").filter { it.isNotBlank() })
                    } else {
                        val meta = try {
                            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                                val doc = PDDocument.load(stream)
                                val info = doc.documentInformation
                                val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                                val a = info?.author?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                                val tp = if (info?.creator == "Margin") PdfType.NOTE else PdfType.DOCUMENT
                                val pr = readProjectsFromXmp(doc)
                                doc.close()
                                Quad(t, a, tp, pr)
                            } ?: Quad("", emptyList(), PdfType.DOCUMENT, emptyList())
                        } catch (e: Exception) {
                            Quad("", emptyList<String>(), PdfType.DOCUMENT, emptyList())
                        }
                        dao.upsert(PdfMetadataEntity(uriStr, name, meta.first, meta.second.joinToString(";"), lastModified, meta.third, meta.fourth.joinToString(",")))
                        meta
                    }
                    pdfs.add(FileSystemItem.PdfItem(PdfFile(uri = file.uri, name = name, title = title, authors = authors, lastModified = lastModified, type = type, projects = projects)))
                }
            }
        }
        dirs + pdfs
    }

    suspend fun createDirectory(rootUri: Uri, pathFromRoot: List<String>, name: String): Boolean = withContext(Dispatchers.IO) {
        val dir = navigateToDir(rootUri, pathFromRoot) ?: return@withContext false
        dir.createDirectory(name) != null
    }

    suspend fun syncWithFilesystem(rootUri: Uri) = withContext(Dispatchers.IO) {
        // Collect all PDFs from the filesystem recursively
        val found = mutableMapOf<String, DocumentFile>() // uri string -> DocumentFile
        fun scanDir(dir: DocumentFile) {
            for (file in dir.listFiles().filter { it.name?.startsWith(".") != true }) {
                when {
                    file.isDirectory -> scanDir(file)
                    file.isFile && file.type == "application/pdf" -> found[file.uri.toString()] = file
                }
            }
        }
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext
        scanDir(root)

        // Remove DB entries whose files no longer exist
        val dbUris = dao.getAll().map { it.uri }.toSet()
        for (uriStr in dbUris - found.keys) {
            dao.deleteByUri(uriStr)
        }

        // Add/update entries for files not in DB or with changed lastModified
        for ((uriStr, file) in found) {
            val name = file.name ?: "Untitled.pdf"
            val lastModified = file.lastModified()
            val cached = dao.getByUri(uriStr)
            if (cached != null && cached.lastModified == lastModified) continue
            val (title, authors, type, projects) = try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    val info = doc.documentInformation
                    val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                    val a = info?.author?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    val tp = if (info?.creator == "Margin") PdfType.NOTE else PdfType.DOCUMENT
                    val pr = readProjectsFromXmp(doc)
                    doc.close()
                    Quad(t, a, tp, pr)
                } ?: Quad("", emptyList(), PdfType.DOCUMENT, emptyList())
            } catch (e: Exception) {
                Quad("", emptyList<String>(), PdfType.DOCUMENT, emptyList())
            }
            dao.upsert(PdfMetadataEntity(uriStr, name, title, authors.joinToString(";"), lastModified, type, projects.joinToString(",")))
        }
    }

    suspend fun getAllPdfs(): List<PdfFile> = withContext(Dispatchers.IO) {
        dao.getAll().map { entity ->
            PdfFile(
                uri = Uri.parse(entity.uri),
                name = entity.name,
                title = entity.title,
                authors = entity.author.split(";").map { it.trim() }.filter { it.isNotBlank() },
                lastModified = entity.lastModified,
                type = entity.type,
                projects = entity.projects.split(",").filter { it.isNotBlank() },
                lastOpened = entity.lastOpened
            )
        }
    }

    suspend fun recordOpen(dirUri: Uri, docUri: Uri) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        dao.updateLastOpened(docUri.toString(), timestamp)
        _pdfOpenedFlow.emit(docUri to timestamp)
        val docId = DocumentsContract.getDocumentId(docUri)
        if (_currentDocId != docId) {
            _currentDocId?.let { _previousDocParams.value = dirUri to it }
            _currentDocId = docId
        }
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
            val lastModified = destFile.lastModified()
            val (title, authors, type, projects) = try {
                context.contentResolver.openInputStream(destFile.uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    val info = doc.documentInformation
                    val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                    val a = info?.author?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    val tp = if (info?.creator == "Margin") PdfType.NOTE else PdfType.DOCUMENT
                    val pr = readProjectsFromXmp(doc)
                    doc.close()
                    Quad(t, a, tp, pr)
                } ?: Quad("", emptyList(), PdfType.DOCUMENT, emptyList())
            } catch (e: Exception) {
                Quad("", emptyList<String>(), PdfType.DOCUMENT, emptyList())
            }
            dao.upsert(PdfMetadataEntity(destFile.uri.toString(), name, title, authors.joinToString(";"), lastModified, type, projects.joinToString(",")))
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

    suspend fun removeFromDatabase(uri: Uri) = withContext(Dispatchers.IO) {
        dao.deleteByUri(uri.toString())
    }

    suspend fun updateMetadata(uri: Uri, title: String, authors: List<String>, projects: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                    ?: return@withContext false
                val info = doc.documentInformation
                info.title = title.ifBlank { null }
                info.author = authors.joinToString("; ").ifBlank { null }
                writeProjectsToXmp(doc, projects)
                save(doc, uri)
                doc.close()
                val uriStr = uri.toString()
                val lastModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
                val existingEntity = dao.getByUri(uriStr)
                val existingName = existingEntity?.name ?: ""
                val existingType = existingEntity?.type ?: PdfType.DOCUMENT
                dao.upsert(PdfMetadataEntity(uriStr, existingName, title.ifBlank { "" }, authors.joinToString(";"), lastModified, existingType, projects.joinToString(","), System.currentTimeMillis()))
                true
            } catch (e: Exception) {
                false
            }
        }

    suspend fun syncMetadataToDb(uri: Uri, title: String, authors: List<String>, projects: List<String>) =
        withContext(Dispatchers.IO) {
            val uriStr = uri.toString()
            val lastModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
            val existingEntity = dao.getByUri(uriStr)
            val existingName = existingEntity?.name ?: ""
            val existingType = existingEntity?.type ?: PdfType.DOCUMENT
            dao.upsert(PdfMetadataEntity(uriStr, existingName, title.ifBlank { "" }, authors.joinToString(";"), lastModified, existingType, projects.joinToString(","), System.currentTimeMillis()))
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
            val title = "Note on $yyyy.$mm.$dd at $hh:$roundedMin"
            val doc = PDDocument()
            val info = doc.documentInformation
            info.creator = "Margin"
            info.title = title
            info.setCreationDate(Calendar.getInstance())
            doc.documentInformation = info
            val page = PDPage(PDRectangle.A4)
            page.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
            doc.addPage(page)
            context.contentResolver.openOutputStream(destFile.uri)?.use { doc.save(it) }
            doc.close()
            val lastModified = destFile.lastModified()
            dao.upsert(PdfMetadataEntity(destFile.uri.toString(), name, title, "", lastModified, PdfType.NOTE))
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

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
