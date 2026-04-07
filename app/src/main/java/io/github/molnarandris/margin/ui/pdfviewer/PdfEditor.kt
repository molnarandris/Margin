package io.github.molnarandris.margin.ui.pdfviewer

import android.app.Application
import android.graphics.RectF
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationUnknown
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import io.github.molnarandris.margin.data.PdfRepository

data class TextChar(val text: String, val bounds: RectF)

data class TextWord(
    val text: String,
    val bounds: RectF,   // PR space (top-left origin)
    val chars: List<TextChar>
)

class PdfEditor(
    private val application: Application,
    private val pdfRepository: PdfRepository
) {

    // ---- Annotation Persistence ----

    /** Must be called within renderMutex.withLock, with renderer/pfd already closed. */
    suspend fun writeInkStrokesToPdf(uri: Uri, strokesByPage: Map<Int, List<InkStroke>>) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        strokesByPage.forEach { (pageIndex, strokes) ->
            strokes.forEach { stroke -> addInkAnnotationToDoc(pdDoc, pageIndex, stroke) }
        }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    /**
     * Writes [alsoWritePending] first (already-filtered pending strokes), then removes
     * annotations named by [namesToRemove] on [pageIndex], then adds [strokesToAdd].
     * Must be called within renderMutex.withLock, with renderer/pfd already closed.
     */
    suspend fun removeInkAnnotationsAndAdd(
        uri: Uri,
        pageIndex: Int,
        namesToRemove: Set<String>,
        strokesToAdd: List<InkStroke>,
        alsoWritePending: Map<Int, List<InkStroke>> = emptyMap()
    ) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        alsoWritePending.forEach { (pi, strokes) ->
            strokes.forEach { s -> addInkAnnotationToDoc(pdDoc, pi, s) }
        }
        if (namesToRemove.isNotEmpty()) {
            val annotations = pdDoc.getPage(pageIndex).annotations
            annotations.removeAll(annotations.filter { it.annotationName in namesToRemove })
        }
        for (s in strokesToAdd) addInkAnnotationToDoc(pdDoc, pageIndex, s)
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    /**
     * Adds a highlight annotation built from [lineBounds] (PR-space RectF, one per line).
     * Returns the refreshed highlight list for the page so the caller can replace the
     * optimistic entry with the real annotationIndex.
     * Must be called within renderMutex.withLock, with renderer/pfd already closed.
     */
    suspend fun addHighlightAnnotation(
        uri: Uri,
        pageIndex: Int,
        lineBounds: List<RectF>,
        note: String?
    ): List<PdfHighlight> {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val pdPage = pdDoc.getPage(pageIndex)
        val pageH = pdPage.mediaBox.height

        val quads = FloatArray(lineBounds.size * 8)
        lineBounds.forEachIndexed { i, rect ->
            val pbTop = pageH - rect.top
            val pbBot = pageH - rect.bottom
            val base = i * 8
            quads[base+0] = rect.left;  quads[base+1] = pbTop
            quads[base+2] = rect.right; quads[base+3] = pbTop
            quads[base+4] = rect.left;  quads[base+5] = pbBot
            quads[base+6] = rect.right; quads[base+7] = pbBot
        }
        val allLeft   = lineBounds.minOf { it.left }
        val allRight  = lineBounds.maxOf { it.right }
        val allTop    = lineBounds.minOf { it.top }
        val allBottom = lineBounds.maxOf { it.bottom }
        val pbAllTop  = pageH - allTop
        val pbAllBot  = pageH - allBottom

        val ann = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT).apply {
            quadPoints = quads
            color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
            rectangle = PDRectangle(allLeft, pbAllBot, allRight - allLeft, pbAllTop - pbAllBot)
            if (!note.isNullOrBlank()) contents = note
        }
        pdPage.annotations.add(ann)

        pdfRepository.save(pdDoc, uri)
        val newHighlights = extractHighlights(pdDoc, pageIndex, pageH)
        pdDoc.close()
        return newHighlights
    }

    /** Must be called within renderMutex.withLock, with renderer/pfd already closed. */
    suspend fun deleteHighlightAnnotation(uri: Uri, pageIndex: Int, annotationIndex: Int) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val annotations = pdDoc.getPage(pageIndex).annotations
        if (annotationIndex in annotations.indices) {
            annotations.removeAt(annotationIndex)
        }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    /** Must be called within renderMutex.withLock, with renderer/pfd already closed. */
    suspend fun setHighlightNoteInPdf(uri: Uri, pageIndex: Int, annotationIndex: Int, note: String) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val ann = pdDoc.getPage(pageIndex).annotations.getOrNull(annotationIndex)
        if (ann != null) {
            if (note.isBlank()) ann.getCOSObject().removeItem(COSName.CONTENTS)
            else ann.contents = note
        }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    // ---- Document Structure ----

    /** Must be called within renderMutex.withLock, with renderer/pfd already closed. */
    suspend fun insertPageInDoc(uri: Uri, insertBeforeIndex: Int) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val mediaBox = pdDoc.getPage(insertBeforeIndex.coerceIn(0, pdDoc.numberOfPages - 1)).mediaBox
        val newPage = PDPage(mediaBox)
        newPage.cosObject.setBoolean(COSName.getPDFName("MarginApp"), true)
        if (insertBeforeIndex >= pdDoc.numberOfPages) {
            pdDoc.addPage(newPage)
        } else {
            pdDoc.pages.insertBefore(newPage, pdDoc.getPage(insertBeforeIndex))
        }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    /** Must be called within renderMutex.withLock, with renderer/pfd already closed. */
    suspend fun deletePageFromDoc(uri: Uri, pageIndex: Int) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        pdDoc.removePage(pageIndex)
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    /** Must be called within renderMutex.withLock, with renderer/pfd already closed. */
    suspend fun setMetadataInDoc(uri: Uri, newTitle: String, newAuthors: List<String>, newProjects: List<String>) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val info = pdDoc.documentInformation
        info.title  = newTitle
        info.author = newAuthors.joinToString("; ").ifBlank { null }
        pdDoc.documentInformation = info
        PdfRepository.writeProjectsToXmp(pdDoc, newProjects)
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    // ---- Data Extraction ----

    fun extractAllWords(pdDoc: PDDocument, pageCount: Int): List<List<TextWord>> {
        return try {
            val extractor = WordExtractor(pageCount)
            extractor.getText(pdDoc)
            extractor.wordsByPage.map { it.toList() }
        } catch (e: Exception) {
            List(pageCount) { emptyList() }
        }
    }

    fun extractWords(pdDoc: PDDocument, pageIndex: Int): List<TextWord> {
        return try {
            val extractor = SinglePageWordExtractor()
            extractor.startPage = pageIndex + 1
            extractor.endPage   = pageIndex + 1
            extractor.getText(pdDoc)
            extractor.words.toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun extractInkStrokes(pdDoc: PDDocument, pageIndex: Int, pageW: Float, pageH: Float): List<InkStroke> {
        return try {
            val pdPage = pdDoc.getPage(pageIndex)
            val result = mutableListOf<InkStroke>()
            for (ann in pdPage.annotations) {
                if (ann.getCOSObject().getNameAsString(COSName.SUBTYPE) != "Ink") continue
                val name = ann.annotationName ?: continue
                val id = name.removePrefix("ink-").toIntOrNull() ?: continue
                val outerArr = ann.getCOSObject().getDictionaryObject(COSName.INKLIST) as? COSArray ?: continue
                for (i in 0 until outerArr.size()) {
                    val innerArr = outerArr.getObject(i) as? COSArray ?: continue
                    val points = mutableListOf<Offset>()
                    var j = 0
                    while (j + 1 < innerArr.size()) {
                        val x = (innerArr.getObject(j) as? COSNumber)?.floatValue() ?: break
                        val y = (innerArr.getObject(j + 1) as? COSNumber)?.floatValue() ?: break
                        points.add(Offset(x / pageW, 1f - y / pageH))
                        j += 2
                    }
                    val cosObj = ann.getCOSObject()

                    val cArr = cosObj.getDictionaryObject(COSName.getPDFName("C")) as? COSArray
                    val strokeColor = if (cArr != null && cArr.size() >= 3) {
                        val r = (cArr.getObject(0) as? COSNumber)?.floatValue() ?: 0f
                        val g = (cArr.getObject(1) as? COSNumber)?.floatValue() ?: 0f
                        val b = (cArr.getObject(2) as? COSNumber)?.floatValue() ?: 0f
                        StrokeColor.entries.minByOrNull { c ->
                            val dr = c.pdfRgb[0]-r; val dg = c.pdfRgb[1]-g; val db = c.pdfRgb[2]-b
                            dr*dr + dg*dg + db*db
                        } ?: StrokeColor.BLACK
                    } else StrokeColor.BLACK

                    val bsDict2 = cosObj.getDictionaryObject(COSName.getPDFName("BS")) as? COSDictionary
                    val w = (bsDict2?.getDictionaryObject(COSName.getPDFName("W")) as? COSNumber)?.floatValue() ?: 1.5f
                    val strokeThickness = StrokeThickness.entries.minByOrNull { kotlin.math.abs(it.multiplier * 1.5f - w) }
                        ?: StrokeThickness.MEDIUM

                    val roundCap = cosObj.getDictionaryObject(COSName.AP) != null
                    if (points.isNotEmpty()) result.add(InkStroke(id, points, strokeColor, strokeThickness, roundCap))
                }
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun extractHighlights(pdDoc: PDDocument, pageIndex: Int, pageHeight: Float): List<PdfHighlight> {
        return try {
            val pdPage = pdDoc.getPage(pageIndex)
            // Keep the real index into pdPage.annotations so deletion removes the right entry
            pdPage.annotations
                .mapIndexed { realIdx, ann -> realIdx to ann }
                .filter { (_, ann) ->
                    ann is PDAnnotationTextMarkup &&
                    (ann as PDAnnotationTextMarkup).subtype == PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT
                }
                .map { (realIdx, ann) ->
                    ann as PDAnnotationTextMarkup
                    val quads = ann.quadPoints ?: floatArrayOf()
                    val rects = quads.toList().chunked(8).mapNotNull { q ->
                        if (q.size < 8) return@mapNotNull null
                        val minX  = minOf(q[0], q[2], q[4], q[6])
                        val maxX  = maxOf(q[0], q[2], q[4], q[6])
                        val pbTop = maxOf(q[1], q[3], q[5], q[7])
                        val pbBot = minOf(q[1], q[3], q[5], q[7])
                        RectF(minX, pageHeight - pbTop, maxX, pageHeight - pbBot)
                    }
                    val note = ann.contents?.takeIf { it.isNotBlank() }
                    PdfHighlight(pageIndex, rects, realIdx, note)
                }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun extractOutline(pdDoc: PDDocument): List<OutlineItem> {
        val result = mutableListOf<OutlineItem>()
        val root = pdDoc.documentCatalog.documentOutline ?: return result
        fun traverse(item: PDOutlineItem?, level: Int) {
            var current = item
            while (current != null) {
                val title = current.title.orEmpty().trim()
                val dest = try { current.destination } catch (e: Exception) { null }
                    ?: try { (current.action as? PDActionGoTo)?.destination } catch (e: Exception) { null }
                val resolvedDest: PDPageDestination? = when (dest) {
                    is PDPageDestination -> dest
                    is PDNamedDestination -> try {
                        pdDoc.documentCatalog.findNamedDestinationPage(dest)
                    } catch (e: Exception) { null }
                    else -> null
                }
                val pageIndex = resolvedDest?.retrievePageNumber() ?: -1
                if (title.isNotEmpty() && pageIndex >= 0) {
                    result.add(OutlineItem(title, pageIndex, level))
                }
                traverse(current.firstChild, level + 1)
                current = current.nextSibling
            }
        }
        traverse(root.firstChild, 0)
        return result.mapIndexed { i, item ->
            item.copy(hasChildren = i + 1 < result.size && result[i + 1].level > result[i].level)
        }
    }

    // ---- Private Helpers ----

    internal fun groupCharsIntoLines(chars: List<TextChar>): List<List<TextChar>> {
        if (chars.isEmpty()) return emptyList()
        val threshold = chars.map { it.bounds.height() }.average().toFloat() * 0.5f
        val lines = mutableListOf<MutableList<TextChar>>()
        for (char in chars) {
            val line = lines.firstOrNull { kotlin.math.abs(it.first().bounds.top - char.bounds.top) < threshold }
            if (line != null) line.add(char) else lines.add(mutableListOf(char))
        }
        return lines
    }

    private fun addInkAnnotationToDoc(pdDoc: PDDocument, pageIndex: Int, stroke: InkStroke) {
        val pdPage = pdDoc.getPage(pageIndex)
        val pageW = pdPage.mediaBox.width
        val pageH = pdPage.mediaBox.height

        val normalized = stroke.points
        val coords = FloatArray(normalized.size * 2) { i ->
            val pt = normalized[i / 2]
            if (i % 2 == 0) pt.x * pageW else pageH - pt.y * pageH
        }
        val minX = coords.filterIndexed { i, _ -> i % 2 == 0 }.min()
        val minY = coords.filterIndexed { i, _ -> i % 2 != 0 }.min()
        val maxX = coords.filterIndexed { i, _ -> i % 2 == 0 }.max()
        val maxY = coords.filterIndexed { i, _ -> i % 2 != 0 }.max()

        val strokeWidth = stroke.thickness.multiplier * 1.5f
        val pad = strokeWidth * 0.7f

        val innerList = COSArray().apply { coords.forEach { add(COSFloat(it)) } }
        val outerList = COSArray().apply { add(innerList) }
        val rectArr = COSArray().apply {
            listOf(minX - pad, minY - pad, maxX + pad, maxY + pad).forEach { add(COSFloat(it)) }
        }
        val colorArr = COSArray().apply {
            stroke.color.pdfRgb.forEach { add(COSFloat(it)) }
        }
        val bsDict = COSDictionary().apply {
            setName(COSName.TYPE, "Border")
            setName(COSName.SUBTYPE, "S")
            setItem(COSName.getPDFName("W"), COSFloat(stroke.thickness.multiplier * 1.5f))
        }

        val rgb = stroke.color.pdfRgb
        val apContent = buildString {
            append("q ")
            append("1 J ")  // round line cap
            append("1 j ")  // round line join
            append("%.4f w ".format(strokeWidth))
            append("%.4f %.4f %.4f RG ".format(rgb[0], rgb[1], rgb[2]))
            for (i in coords.indices step 2) {
                val op = if (i == 0) "m" else "l"
                append("%.4f %.4f $op ".format(coords[i], coords[i + 1]))
            }
            append("S Q")
        }.toByteArray()
        val apStream = PDStream(pdDoc)
        apStream.createOutputStream().use { it.write(apContent) }
        val apCos = apStream.cosObject
        apCos.setName(COSName.TYPE, "XObject")
        apCos.setName(COSName.SUBTYPE, "Form")
        apCos.setItem(COSName.BBOX, COSArray().apply {
            listOf(minX - pad, minY - pad, maxX + pad, maxY + pad).forEach { add(COSFloat(it)) }
        })
        val apDict = COSDictionary().apply { setItem(COSName.N, apCos) }

        val annDict = COSDictionary().apply {
            setName(COSName.TYPE, "Annot")
            setName(COSName.SUBTYPE, "Ink")
            setItem(COSName.INKLIST, outerList)
            setItem(COSName.RECT, rectArr)
            setItem(COSName.getPDFName("C"), colorArr)
            setItem(COSName.getPDFName("BS"), bsDict)
            setItem(COSName.AP, apDict)
            setString(COSName.NM, "ink-${stroke.id}")
        }
        pdPage.annotations.add(PDAnnotationUnknown(annDict))
    }

    // ---- Private Classes ----

    private class WordExtractor(private val pageCount: Int) : PDFTextStripper() {
        val wordsByPage: Array<MutableList<TextWord>> = Array(pageCount) { mutableListOf() }
        private val currentWordChars = mutableListOf<TextPosition>()

        override fun writeString(text: String, positions: List<TextPosition>) {
            for (pos in positions) {
                if (pos.unicode.isBlank()) flushWord() else currentWordChars.add(pos)
            }
            flushWord()
        }

        private fun flushWord() {
            if (currentWordChars.isEmpty()) return
            val pageIdx = currentPageNo - 1  // PDFTextStripper is 1-based
            if (pageIdx !in wordsByPage.indices) { currentWordChars.clear(); return }
            val text   = currentWordChars.joinToString("") { it.unicode }
            val left   = currentWordChars.minOf { it.x }
            val right  = currentWordChars.maxOf { it.x + it.width }
            val top    = currentWordChars.minOf { it.y }
            val bottom = currentWordChars.maxOf { it.y + it.height }
            val chars  = currentWordChars.map { pos ->
                TextChar(pos.unicode, RectF(pos.x, pos.y, pos.x + pos.width, pos.y + pos.height))
            }
            wordsByPage[pageIdx].add(TextWord(text, RectF(left, top, right, bottom), chars))
            currentWordChars.clear()
        }
    }

    private class SinglePageWordExtractor : PDFTextStripper() {
        val words = mutableListOf<TextWord>()
        private val currentWordChars = mutableListOf<TextPosition>()

        override fun writeString(text: String, positions: List<TextPosition>) {
            for (pos in positions) {
                if (pos.unicode.isBlank()) flushWord() else currentWordChars.add(pos)
            }
            flushWord()
        }

        private fun flushWord() {
            if (currentWordChars.isEmpty()) return
            val text   = currentWordChars.joinToString("") { it.unicode }
            val left   = currentWordChars.minOf { it.x }
            val right  = currentWordChars.maxOf { it.x + it.width }
            val top    = currentWordChars.minOf { it.y }
            val bottom = currentWordChars.maxOf { it.y + it.height }
            val chars  = currentWordChars.map { pos ->
                TextChar(pos.unicode, RectF(pos.x, pos.y, pos.x + pos.width, pos.y + pos.height))
            }
            words.add(TextWord(text, RectF(left, top, right, bottom), chars))
            currentWordChars.clear()
        }
    }
}
