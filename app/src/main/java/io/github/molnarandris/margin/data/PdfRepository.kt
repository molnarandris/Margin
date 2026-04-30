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
import kotlinx.coroutines.flow.first
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

data class PdfFile(val uri: Uri, val name: String, val title: String = "", val authors: List<String> = emptyList(), val lastModified: Long = 0L, val type: PdfType = PdfType.DOCUMENT, val projects: List<String> = emptyList(), val lastOpened: Long = 0L, val createdAt: Long = 0L, val people: List<String> = emptyList(), val arxivId: String = "")

sealed class FileSystemItem {
    data class PdfItem(val pdf: PdfFile) : FileSystemItem()
    data class DirItem(val uri: Uri, val name: String, val lastModified: Long = 0L) : FileSystemItem()
}

class PdfRepository(private val context: Context) {

    private val prefsRepo = PreferencesRepository(context)

    fun backupFileFor(uri: Uri): File =
        File(context.filesDir, "backup_${uri.toString().hashCode()}.pdf")

    suspend fun save(doc: PDDocument, uri: Uri) {
        fileWriteLockFor(uri).withLock {
            val backup = backupFileFor(uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use { input.copyTo(it) }
            }
            val rounded = roundToHalfHour(Calendar.getInstance())
            doc.documentInformation.setModificationDate(rounded)
            writeDatesToXmp(doc, createDate = null, modDate = rounded)
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
        private const val DC_NS = "http://purl.org/dc/elements/1.1/"
        private const val XMP_NS = "http://ns.adobe.com/xap/1.0/"

        fun roundToHalfHour(cal: Calendar): Calendar = (cal.clone() as Calendar).also {
            it.set(Calendar.MINUTE, if (it.get(Calendar.MINUTE) >= 30) 30 else 0)
            it.set(Calendar.SECOND, 0)
            it.set(Calendar.MILLISECOND, 0)
        }

        private fun calendarToXmpDate(cal: Calendar): String =
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
                .also { it.timeZone = cal.timeZone }
                .format(cal.time)

        fun writeDatesToXmp(doc: PDDocument, createDate: Calendar?, modDate: Calendar?) {
            if (createDate == null && modDate == null) return
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

            if (createDate != null) {
                val existing = xmpDoc.getElementsByTagNameNS(XMP_NS, "CreateDate")
                repeat(existing.length) { existing.item(0).parentNode.removeChild(existing.item(0)) }
            }
            if (modDate != null) {
                val existing = xmpDoc.getElementsByTagNameNS(XMP_NS, "ModifyDate")
                repeat(existing.length) { existing.item(0).parentNode.removeChild(existing.item(0)) }
            }

            val rdfNodes = xmpDoc.getElementsByTagNameNS(RDF_NS, "RDF")
            val rdf: Element = if (rdfNodes.length > 0) rdfNodes.item(0) as Element else {
                val r = xmpDoc.createElementNS(RDF_NS, "rdf:RDF")
                r.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:rdf", RDF_NS)
                xmpDoc.documentElement.appendChild(r)
                r
            }
            var desc: Element? = null
            val descs = xmpDoc.getElementsByTagNameNS(RDF_NS, "Description")
            for (i in 0 until descs.length) {
                val d = descs.item(i) as Element
                if (d.getAttribute("xmlns:xmp") == XMP_NS) { desc = d; break }
            }
            if (desc == null) {
                desc = xmpDoc.createElementNS(RDF_NS, "rdf:Description")
                desc.setAttributeNS(RDF_NS, "rdf:about", "")
                desc.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:xmp", XMP_NS)
                rdf.appendChild(desc)
            }
            if (createDate != null) {
                val el = xmpDoc.createElementNS(XMP_NS, "xmp:CreateDate")
                el.textContent = calendarToXmpDate(createDate)
                desc.appendChild(el)
            }
            if (modDate != null) {
                val el = xmpDoc.createElementNS(XMP_NS, "xmp:ModifyDate")
                el.textContent = calendarToXmpDate(modDate)
                desc.appendChild(el)
            }

            val bytes = ByteArrayOutputStream().also { out ->
                TransformerFactory.newInstance().newTransformer()
                    .transform(DOMSource(xmpDoc), StreamResult(out))
            }.toByteArray()
            doc.documentCatalog.metadata = PDMetadata(doc, ByteArrayInputStream(bytes))
        }

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

        fun readArxivFromXmp(doc: PDDocument): String {
            val metaStream = doc.documentCatalog.metadata ?: return ""
            return try {
                val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                val xmlDoc = factory.newDocumentBuilder().parse(metaStream.exportXMPMetadata())
                val nodes = xmlDoc.getElementsByTagNameNS(MARGIN_NS, "ArxivId")
                if (nodes.length == 0) "" else nodes.item(0).textContent.trim()
            } catch (e: Exception) { "" }
        }

        fun writeArxivToXmp(doc: PDDocument, arxivId: String) {
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

            val existing = xmpDoc.getElementsByTagNameNS(MARGIN_NS, "ArxivId")
            repeat(existing.length) { existing.item(0).parentNode.removeChild(existing.item(0)) }

            if (arxivId.isNotBlank()) {
                val rdfNodes = xmpDoc.getElementsByTagNameNS(RDF_NS, "RDF")
                val rdf: Element = if (rdfNodes.length > 0) rdfNodes.item(0) as Element else {
                    val r = xmpDoc.createElementNS(RDF_NS, "rdf:RDF")
                    r.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:rdf", RDF_NS)
                    xmpDoc.documentElement.appendChild(r)
                    r
                }

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

                val arxivProp = xmpDoc.createElementNS(MARGIN_NS, "margin:ArxivId")
                arxivProp.textContent = arxivId
                desc.appendChild(arxivProp)
            }

            val bytes = ByteArrayOutputStream().also { out ->
                TransformerFactory.newInstance().newTransformer()
                    .transform(DOMSource(xmpDoc), StreamResult(out))
            }.toByteArray()

            doc.documentCatalog.metadata = PDMetadata(doc, ByteArrayInputStream(bytes))
        }

        fun readPeopleFromXmp(doc: PDDocument): List<String> {
            val metaStream = doc.documentCatalog.metadata ?: return emptyList()
            return try {
                val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                val xmlDoc = factory.newDocumentBuilder().parse(metaStream.exportXMPMetadata())
                val seqNodes = xmlDoc.getElementsByTagNameNS(DC_NS, "contributor")
                if (seqNodes.length == 0) return emptyList()
                val lis = (seqNodes.item(0) as Element).getElementsByTagNameNS(RDF_NS, "li")
                (0 until lis.length).map { lis.item(it).textContent.trim() }.filter { it.isNotBlank() }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun writePeopleToXmp(doc: PDDocument, people: List<String>) {
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

            val existing = xmpDoc.getElementsByTagNameNS(DC_NS, "contributor")
            repeat(existing.length) { existing.item(0).parentNode.removeChild(existing.item(0)) }

            if (people.isNotEmpty()) {
                val rdfNodes = xmpDoc.getElementsByTagNameNS(RDF_NS, "RDF")
                val rdf: Element = if (rdfNodes.length > 0) rdfNodes.item(0) as Element else {
                    val r = xmpDoc.createElementNS(RDF_NS, "rdf:RDF")
                    r.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:rdf", RDF_NS)
                    xmpDoc.documentElement.appendChild(r)
                    r
                }

                var desc: Element? = null
                val descs = xmpDoc.getElementsByTagNameNS(RDF_NS, "Description")
                for (i in 0 until descs.length) {
                    val d = descs.item(i) as Element
                    if (d.getAttribute("xmlns:dc") == DC_NS) { desc = d; break }
                }
                if (desc == null) {
                    desc = xmpDoc.createElementNS(RDF_NS, "rdf:Description")
                    desc.setAttributeNS(RDF_NS, "rdf:about", "")
                    desc.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:dc", DC_NS)
                    rdf.appendChild(desc)
                }

                val contributorProp = xmpDoc.createElementNS(DC_NS, "dc:contributor")
                val seq = xmpDoc.createElementNS(RDF_NS, "rdf:Seq")
                for (person in people) {
                    val li = xmpDoc.createElementNS(RDF_NS, "rdf:li")
                    li.textContent = person
                    seq.appendChild(li)
                }
                contributorProp.appendChild(seq)
                desc.appendChild(contributorProp)
            }

            val bytes = ByteArrayOutputStream().also { out ->
                TransformerFactory.newInstance().newTransformer()
                    .transform(DOMSource(xmpDoc), StreamResult(out))
            }.toByteArray()

            doc.documentCatalog.metadata = PDMetadata(doc, ByteArrayInputStream(bytes))
        }

        private fun parseXmpDate(text: String): Calendar? = try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US)
            Calendar.getInstance().also { it.time = sdf.parse(text) ?: return null }
        } catch (e: Exception) { null }

        fun readPageDatesFromXmp(doc: PDDocument): List<Calendar?> {
            val metaStream = doc.documentCatalog.metadata ?: return emptyList()
            return try {
                val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
                val xmlDoc = factory.newDocumentBuilder().parse(metaStream.exportXMPMetadata())
                val seqNodes = xmlDoc.getElementsByTagNameNS(MARGIN_NS, "PageDates")
                if (seqNodes.length == 0) return emptyList()
                val lis = (seqNodes.item(0) as Element).getElementsByTagNameNS(RDF_NS, "li")
                (0 until lis.length).map { i ->
                    val text = lis.item(i).textContent.trim()
                    if (text.isBlank()) null else parseXmpDate(text)
                }
            } catch (e: Exception) {
                emptyList()
            }
        }

        fun writePageDatesToXmp(doc: PDDocument, dates: List<Calendar?>) {
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

            val existing = xmpDoc.getElementsByTagNameNS(MARGIN_NS, "PageDates")
            repeat(existing.length) { existing.item(0).parentNode.removeChild(existing.item(0)) }

            val rdfNodes = xmpDoc.getElementsByTagNameNS(RDF_NS, "RDF")
            val rdf: Element = if (rdfNodes.length > 0) rdfNodes.item(0) as Element else {
                val r = xmpDoc.createElementNS(RDF_NS, "rdf:RDF")
                r.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns:rdf", RDF_NS)
                xmpDoc.documentElement.appendChild(r)
                r
            }

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

            val datesProp = xmpDoc.createElementNS(MARGIN_NS, "margin:PageDates")
            val seq = xmpDoc.createElementNS(RDF_NS, "rdf:Seq")
            for (cal in dates) {
                val li = xmpDoc.createElementNS(RDF_NS, "rdf:li")
                li.textContent = if (cal == null) "" else calendarToXmpDate(cal)
                seq.appendChild(li)
            }
            datesProp.appendChild(seq)
            desc.appendChild(datesProp)

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
                file.isFile && file.type == "application/pdf"
                    && file.name != "scratchpad.pdf" -> {
                    val name = file.name ?: "Untitled.pdf"
                    val uriStr = file.uri.toString()
                    val lastModified = file.lastModified()
                    val cached = dao.getByUri(uriStr)
                    val meta = if (cached != null && cached.lastModified == lastModified && cached.createdAt != 0L) {
                        ScanMeta(cached.title, cached.author.split(";").map { it.trim() }.filter { it.isNotBlank() }, cached.type, cached.projects.split(",").filter { it.isNotBlank() }, cached.createdAt, cached.people.split(",").filter { it.isNotBlank() }, cached.arxivId)
                    } else {
                        val scanned = try {
                            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                                val doc = PDDocument.load(stream)
                                val info = doc.documentInformation
                                val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                                val a = info?.author?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                                val tp = if (info?.creator == "Margin") PdfType.NOTE else PdfType.DOCUMENT
                                val pr = readProjectsFromXmp(doc)
                                val c = info?.creationDate?.timeInMillis ?: 0L
                                val pe = readPeopleFromXmp(doc)
                                val ax = readArxivFromXmp(doc)
                                doc.close()
                                ScanMeta(t, a, tp, pr, c, pe, ax)
                            } ?: ScanMeta("", emptyList(), PdfType.DOCUMENT, emptyList(), 0L, emptyList())
                        } catch (e: Exception) {
                            ScanMeta("", emptyList(), PdfType.DOCUMENT, emptyList(), 0L, emptyList())
                        }
                        dao.upsert(PdfMetadataEntity(uriStr, name, scanned.title, scanned.authors.joinToString(";"), lastModified, scanned.type, scanned.projects.joinToString(","), createdAt = scanned.createdAt, people = scanned.people.joinToString(","), arxivId = scanned.arxivId))
                        scanned
                    }
                    pdfs.add(FileSystemItem.PdfItem(PdfFile(uri = file.uri, name = name, title = meta.title, authors = meta.authors, lastModified = lastModified, type = meta.type, projects = meta.projects, createdAt = meta.createdAt, people = meta.people, arxivId = meta.arxivId)))
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
                    file.isFile && file.type == "application/pdf"
                        && file.name != "scratchpad.pdf" -> found[file.uri.toString()] = file
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
            if (cached != null && cached.lastModified == lastModified && cached.createdAt != 0L) continue
            val scanned = try {
                context.contentResolver.openInputStream(file.uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    val info = doc.documentInformation
                    val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                    val a = info?.author?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    val tp = if (info?.creator == "Margin") PdfType.NOTE else PdfType.DOCUMENT
                    val pr = readProjectsFromXmp(doc)
                    val c = info?.creationDate?.timeInMillis ?: 0L
                    val pe = readPeopleFromXmp(doc)
                    doc.close()
                    ScanMeta(t, a, tp, pr, c, pe)
                } ?: ScanMeta("", emptyList(), PdfType.DOCUMENT, emptyList(), 0L, emptyList())
            } catch (e: Exception) {
                ScanMeta("", emptyList(), PdfType.DOCUMENT, emptyList(), 0L, emptyList())
            }
            dao.upsert(PdfMetadataEntity(uriStr, name, scanned.title, scanned.authors.joinToString(";"), lastModified, scanned.type, scanned.projects.joinToString(","), createdAt = scanned.createdAt, people = scanned.people.joinToString(",")))
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
                lastOpened = entity.lastOpened,
                createdAt = entity.createdAt,
                people = entity.people.split(",").filter { it.isNotBlank() },
                arxivId = entity.arxivId
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

    suspend fun importPdf(sourceUri: Uri, rootUri: Uri, pathFromRoot: List<String>): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = navigateToDir(rootUri, pathFromRoot) ?: return@withContext null
            val name = resolveFileName(sourceUri)
            val destFile = dir.createFile("application/pdf", name) ?: return@withContext null
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                context.contentResolver.openOutputStream(destFile.uri)?.use { output ->
                    input.copyTo(output)
                }
            }
            val lastModified = destFile.lastModified()
            val scanned = try {
                context.contentResolver.openInputStream(destFile.uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    val info = doc.documentInformation
                    val t = info?.title?.takeIf { it.isNotBlank() } ?: ""
                    val a = info?.author?.split(";")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                    val tp = if (info?.creator == "Margin") PdfType.NOTE else PdfType.DOCUMENT
                    val pr = readProjectsFromXmp(doc)
                    val c = info?.creationDate?.timeInMillis ?: 0L
                    val pe = readPeopleFromXmp(doc)
                    val ax = readArxivFromXmp(doc)
                    doc.close()
                    ScanMeta(t, a, tp, pr, c, pe, ax)
                } ?: ScanMeta("", emptyList(), PdfType.DOCUMENT, emptyList(), 0L, emptyList())
            } catch (e: Exception) {
                ScanMeta("", emptyList(), PdfType.DOCUMENT, emptyList(), 0L, emptyList())
            }
            dao.upsert(PdfMetadataEntity(destFile.uri.toString(), name, scanned.title, scanned.authors.joinToString(";"), lastModified, scanned.type, scanned.projects.joinToString(","), createdAt = scanned.createdAt, people = scanned.people.joinToString(","), arxivId = scanned.arxivId))
            destFile.uri
        } catch (e: Exception) {
            null
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

    suspend fun updateMetadata(uri: Uri, title: String, authors: List<String>, projects: List<String>, people: List<String>, arxivId: String = ""): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val doc = context.contentResolver.openInputStream(uri)?.use { PDDocument.load(it) }
                    ?: return@withContext false
                val info = doc.documentInformation
                info.title = title.ifBlank { null }
                info.author = authors.joinToString("; ").ifBlank { null }
                writeProjectsToXmp(doc, projects)
                writePeopleToXmp(doc, people)
                writeArxivToXmp(doc, arxivId)
                save(doc, uri)
                doc.close()
                val uriStr = uri.toString()
                val lastModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
                val existingEntity = dao.getByUri(uriStr)
                val existingName = existingEntity?.name ?: ""
                val existingType = existingEntity?.type ?: PdfType.DOCUMENT
                val existingCreatedAt = existingEntity?.createdAt ?: 0L
                dao.upsert(PdfMetadataEntity(uriStr, existingName, title.ifBlank { "" }, authors.joinToString(";"), lastModified, existingType, projects.joinToString(","), System.currentTimeMillis(), existingCreatedAt, people.joinToString(","), arxivId))
                true
            } catch (e: Exception) {
                false
            }
        }

    suspend fun syncMetadataToDb(uri: Uri, title: String, authors: List<String>, projects: List<String>, people: List<String>, arxivId: String = "") =
        withContext(Dispatchers.IO) {
            val uriStr = uri.toString()
            val lastModified = DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
            val existingEntity = dao.getByUri(uriStr)
            val existingName = existingEntity?.name ?: ""
            val existingType = existingEntity?.type ?: PdfType.DOCUMENT
            val existingCreatedAt = existingEntity?.createdAt ?: 0L
            dao.upsert(PdfMetadataEntity(uriStr, existingName, title.ifBlank { "" }, authors.joinToString(";"), lastModified, existingType, projects.joinToString(","), System.currentTimeMillis(), existingCreatedAt, people.joinToString(","), arxivId))
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
            val dayName = now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            val title = "Note on $dayName $yyyy.$mm.$dd at $hh:$roundedMin"
            val authorString = prefsRepo.userName.first().trim()
            val cal = Calendar.getInstance().also {
                it.set(now.year, now.monthValue - 1, now.dayOfMonth, now.hour,
                       if (now.minute >= 30) 30 else 0, 0)
                it.set(Calendar.MILLISECOND, 0)
            }
            val doc = PDDocument()
            val info = doc.documentInformation
            info.creator = "Margin"
            info.title = title
            if (authorString.isNotEmpty()) info.author = authorString
            info.setCreationDate(cal)
            doc.documentInformation = info
            writeDatesToXmp(doc, createDate = cal, modDate = null)
            val page = PDPage(PDRectangle.A4)
            page.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
            doc.addPage(page)
            context.contentResolver.openOutputStream(destFile.uri)?.use { doc.save(it) }
            doc.close()
            val lastModified = destFile.lastModified()
            dao.upsert(PdfMetadataEntity(destFile.uri.toString(), name, title, authorString, lastModified, PdfType.NOTE, createdAt = cal.timeInMillis))
            destFile.uri
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getOrCreateScratchpad(rootUri: Uri): Uri? = withContext(Dispatchers.IO) {
        try {
            val root = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext null
            root.findFile("scratchpad.pdf")?.takeIf { it.isFile }?.uri
                ?: root.findFile("scratchpad")?.takeIf { it.isFile }?.uri
                ?: run {
                    val destFile = root.createFile("application/pdf", "scratchpad")
                        ?: return@withContext null
                    val doc = PDDocument()
                    val info = doc.documentInformation
                    info.creator = "Margin"
                    info.title = "Scratchpad"
                    doc.documentInformation = info
                    val page = PDPage(PDRectangle.A4)
                    page.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
                    doc.addPage(page)
                    writePageDatesToXmp(doc, listOf(null))
                    context.contentResolver.openOutputStream(destFile.uri)?.use { doc.save(it) }
                    doc.close()
                    destFile.uri
                }
        } catch (e: Exception) { null }
    }

    suspend fun readScratchpadPageDates(uri: Uri): List<Calendar?> = withContext(Dispatchers.IO) {
        try {
            val doc = PDDocument.load(context.contentResolver.openInputStream(uri)!!)
            val dates = readPageDatesFromXmp(doc)
            val pageCount = doc.numberOfPages
            doc.close()
            when {
                dates.size >= pageCount -> dates.take(pageCount)
                else -> dates + List(pageCount - dates.size) { null }
            }
        } catch (e: Exception) { emptyList() }
    }

    suspend fun prepareScratchpad(uri: Uri) = withContext(Dispatchers.IO) {
        fileWriteLockFor(uri).withLock {
            val doc = PDDocument.load(context.contentResolver.openInputStream(uri)!!)
            val rawDates = readPageDatesFromXmp(doc)
            val pageCount = doc.numberOfPages
            val dates: MutableList<Calendar?> = when {
                rawDates.size >= pageCount -> rawDates.take(pageCount).toMutableList()
                else -> (rawDates + List(pageCount - rawDates.size) { null }).toMutableList()
            }

            val cutoff = Calendar.getInstance().also { it.add(Calendar.DAY_OF_YEAR, -14) }
            val toDelete = dates.indices.filter { i -> dates[i] != null && dates[i]!!.before(cutoff) }

            when {
                toDelete.size == pageCount -> {
                    toDelete.sortedDescending().forEach { doc.removePage(it) }
                    dates.clear()
                    val newPage = PDPage(PDRectangle.A4)
                    newPage.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
                    doc.addPage(newPage)
                    dates.add(null)
                }
                toDelete.isNotEmpty() -> {
                    toDelete.sortedDescending().forEach { i ->
                        doc.removePage(i)
                        dates.removeAt(i)
                    }
                }
                pageCount == 0 -> {
                    val newPage = PDPage(PDRectangle.A4)
                    newPage.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
                    doc.addPage(newPage)
                    dates.add(null)
                }
            }

            writePageDatesToXmp(doc, dates)

            // Inline save — avoids re-acquiring fileWriteLockFor(uri) that save() would do
            val backup = backupFileFor(uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                backup.outputStream().use { input.copyTo(it) }
            }
            val rounded = roundToHalfHour(Calendar.getInstance())
            doc.documentInformation.setModificationDate(rounded)
            writeDatesToXmp(doc, createDate = null, modDate = rounded)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { doc.save(it) }
            backup.delete()
            doc.close()
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

private data class ScanMeta(val title: String, val authors: List<String>, val type: PdfType, val projects: List<String>, val createdAt: Long, val people: List<String>, val arxivId: String = "")
