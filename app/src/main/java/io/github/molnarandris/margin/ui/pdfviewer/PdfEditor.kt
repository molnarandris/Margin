package io.github.molnarandris.margin.ui.pdfviewer

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
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

/**
 * Groups [strokes] (in draw order) into word-level clusters for PDF storage.
 * Each cluster will become one Ink annotation with multiple InkList inner arrays.
 *
 * Rules (applied in order; first match wins):
 *  4. Style mismatch (color or thickness) → always new group.
 *  1+2. Time gap > 700 ms AND the stroke does not overlap/touch the current group's
 *       bounding box (x overlap + y within 10% of bbox height) → new group.
 *  3. Inter-stroke distance > 2.5 × average of last 5 intra-word distances
 *     (active only when window has ≥ 2 samples) → new group.
 *
 * Strokes with timestamp == 0L (loaded from PDF or pasted from clipboard) are treated
 * as singletons so they are never re-grouped.
 *
 * All coordinates are normalised 0-1 with y = 0 at top.
 */
internal fun groupStrokesIntoWords(strokes: List<InkStroke>): List<List<InkStroke>> {
    if (strokes.isEmpty()) return emptyList()
    if (strokes.all { it.timestamp == 0L }) return strokes.map { listOf(it) }

    val sorted = strokes.sortedBy { it.timestamp }
    val result = mutableListOf<MutableList<InkStroke>>()
    val distWindow = ArrayDeque<Float>(5)

    fun bboxOf(group: List<InkStroke>): FloatArray {
        val pts = group.flatMap { it.points }
        return floatArrayOf(pts.minOf { it.x }, pts.minOf { it.y },
                            pts.maxOf { it.x }, pts.maxOf { it.y })
    }
    fun strokeBbox(s: InkStroke) = floatArrayOf(
        s.points.minOf { it.x }, s.points.minOf { it.y },
        s.points.maxOf { it.x }, s.points.maxOf { it.y }
    )
    fun overlapsOrNear(gb: FloatArray, sb: FloatArray): Boolean {
        val proximity = (gb[3] - gb[1]) * 0.10f
        return sb[0] <= gb[2] && sb[2] >= gb[0] &&
               sb[1] <= gb[3] + proximity && sb[3] >= gb[1] - proximity
    }
    fun dist(a: Offset, b: Offset): Float {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    result.add(mutableListOf(sorted[0]))

    for (i in 1 until sorted.size) {
        val prev = sorted[i - 1]; val curr = sorted[i]
        val group = result.last()

        // Rule 4: style mismatch
        if (curr.color != group[0].color || curr.thickness != group[0].thickness) {
            result.add(mutableListOf(curr)); distWindow.clear(); continue
        }

        val timeDelta = curr.timestamp - prev.timestamp
        val interDist = dist(prev.points.last(), curr.points.first())

        // Rules 1+2: time threshold with bounding-box override
        if (timeDelta > 700L && !overlapsOrNear(bboxOf(group), strokeBbox(curr))) {
            result.add(mutableListOf(curr)); distWindow.clear(); continue
        }

        // Rule 3: distance spike (need ≥ 2 intra-word samples)
        if (distWindow.size >= 2 && interDist > (distWindow.sum() / distWindow.size) * 2.5f) {
            result.add(mutableListOf(curr)); distWindow.clear(); continue
        }

        // Same group — update window and bbox
        group.add(curr)
        if (distWindow.size == 5) distWindow.removeFirst()
        distWindow.addLast(interDist)
    }
    return result
}

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
            groupStrokesIntoWords(strokes).forEach { group ->
                addInkAnnotationToDoc(pdDoc, pageIndex, group)
            }
        }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    /**
     * Writes [alsoWritePending] first (already-filtered pending strokes), then removes
     * annotations containing any of [strokeIdsToRemove] on [pageIndex] (re-adding any
     * survivors from partially-removed groups), then adds [strokesToAdd].
     * Must be called within renderMutex.withLock, with renderer/pfd already closed.
     */
    suspend fun removeInkAnnotationsAndAdd(
        uri: Uri,
        pageIndex: Int,
        strokeIdsToRemove: Set<Int>,
        strokesToAdd: List<InkStroke>,
        alsoWritePending: Map<Int, List<InkStroke>> = emptyMap()
    ) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        alsoWritePending.forEach { (pi, strokes) ->
            groupStrokesIntoWords(strokes).forEach { group ->
                addInkAnnotationToDoc(pdDoc, pi, group)
            }
        }
        if (strokeIdsToRemove.isNotEmpty()) {
            val pdPage = pdDoc.getPage(pageIndex)
            val pageW = pdPage.mediaBox.width
            val pageH = pdPage.mediaBox.height
            val annotations = pdPage.annotations
            val toRemove = mutableListOf<com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation>()
            val survivors = mutableListOf<InkStroke>()

            for (ann in annotations) {
                if (ann.getCOSObject().getNameAsString(COSName.SUBTYPE) != "Ink") continue
                val ids = parseNmIds(ann.annotationName ?: continue) ?: continue
                if (ids.none { it in strokeIdsToRemove }) continue
                toRemove.add(ann)
                val outerArr = ann.getCOSObject().getDictionaryObject(COSName.INKLIST) as? COSArray
                    ?: continue
                val strokeColor = parseColor(ann.getCOSObject())
                val strokeThickness = parseThickness(ann.getCOSObject())
                val roundCap = ann.getCOSObject().getDictionaryObject(COSName.AP) != null
                for ((arrayIdx, strokeId) in ids.withIndex()) {
                    if (strokeId in strokeIdsToRemove) continue
                    val innerArr = outerArr.getObject(arrayIdx) as? COSArray ?: continue
                    val points = mutableListOf<Offset>()
                    var j = 0
                    while (j + 1 < innerArr.size()) {
                        val x = (innerArr.getObject(j) as? COSNumber)?.floatValue() ?: break
                        val y = (innerArr.getObject(j + 1) as? COSNumber)?.floatValue() ?: break
                        points.add(Offset(x / pageW, 1f - y / pageH))
                        j += 2
                    }
                    if (points.isNotEmpty()) survivors.add(
                        InkStroke(strokeId, points, strokeColor, strokeThickness, roundCap)
                    )
                }
            }
            annotations.removeAll(toRemove)
            // Re-add survivors as singletons (timestamp=0 → each is its own group, which is
            // correct: they were already part of a committed group and have no new context)
            survivors.forEach { s -> addInkAnnotationToDoc(pdDoc, pageIndex, listOf(s)) }
        }
        for (s in strokesToAdd) addInkAnnotationToDoc(pdDoc, pageIndex, listOf(s))
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

    /**
     * Opens the document at [uri], extracts words and highlights for [pageIndex], closes it.
     * [pageHeight] should be the rendered page height used to flip PDF coordinates to PR space.
     * Ink strokes are intentionally excluded — in-memory state is authoritative for those.
     */
    suspend fun loadPageData(
        uri: Uri,
        pageIndex: Int,
        pageHeight: Float
    ): Pair<List<TextWord>, List<PdfHighlight>> {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val words = extractWords(pdDoc, pageIndex)
        val highlights = extractHighlights(pdDoc, pageIndex, pageHeight)
        pdDoc.close()
        return words to highlights
    }

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
                val ids = parseNmIds(ann.annotationName ?: continue) ?: continue
                val outerArr = ann.getCOSObject().getDictionaryObject(COSName.INKLIST) as? COSArray ?: continue
                val strokeColor = parseColor(ann.getCOSObject())
                val strokeThickness = parseThickness(ann.getCOSObject())
                val roundCap = ann.getCOSObject().getDictionaryObject(COSName.AP) != null
                val apPoints = parseApStrokePoints(ann.getCOSObject(), pageW, pageH)
                for (i in 0 until outerArr.size()) {
                    val innerArr = outerArr.getObject(i) as? COSArray ?: continue
                    val strokeId = ids.getOrNull(i) ?: continue
                    val apStroke = apPoints?.getOrNull(i)
                    val inkListCount = innerArr.size() / 2
                    val points: List<Offset> = if (apStroke != null && apStroke.size > inkListCount) {
                        apStroke
                    } else {
                        val pts = mutableListOf<Offset>()
                        var j = 0
                        while (j + 1 < innerArr.size()) {
                            val x = (innerArr.getObject(j) as? COSNumber)?.floatValue() ?: break
                            val y = (innerArr.getObject(j + 1) as? COSNumber)?.floatValue() ?: break
                            pts.add(Offset(x / pageW, 1f - y / pageH))
                            j += 2
                        }
                        pts
                    }
                    if (points.isNotEmpty()) result.add(InkStroke(strokeId, points, strokeColor, strokeThickness, roundCap))
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

    // All strokes in [group] share the same color and thickness (enforced by groupStrokesIntoWords).
    private fun addInkAnnotationToDoc(pdDoc: PDDocument, pageIndex: Int, group: List<InkStroke>) {
        require(group.isNotEmpty())
        val stroke0 = group[0]
        val pdPage = pdDoc.getPage(pageIndex)
        val pageW = pdPage.mediaBox.width
        val pageH = pdPage.mediaBox.height

        val strokeWidth = stroke0.thickness.multiplier * 1.5f
        val pad = strokeWidth * 0.7f
        val rgb = stroke0.color.pdfRgb

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE

        val outerList = COSArray()
        val apBuilder = StringBuilder()
        apBuilder.append("q ")
        apBuilder.append("1 J 1 j ")
        apBuilder.append("%.3f w ".format(strokeWidth))
        apBuilder.append("%.3f %.3f %.3f RG ".format(rgb[0], rgb[1], rgb[2]))

        for (stroke in group) {
            val coords = FloatArray(stroke.points.size * 2) { i ->
                val pt = stroke.points[i / 2]
                if (i % 2 == 0) pt.x * pageW else pageH - pt.y * pageH
            }
            for (i in coords.indices) {
                if (i % 2 == 0) { if (coords[i] < minX) minX = coords[i]; if (coords[i] > maxX) maxX = coords[i] }
                else            { if (coords[i] < minY) minY = coords[i]; if (coords[i] > maxY) maxY = coords[i] }
            }
            // InkList stores only start+end; the full bezier path lives in the AP stream.
            val inkCoords = floatArrayOf(coords[0], coords[1], coords[coords.size - 2], coords[coords.size - 1])
            val innerList = COSArray().apply { inkCoords.forEach { add(COSFloat(Math.round(it * 1000) / 1000f)) } }
            outerList.add(innerList)
            apBuilder.append(buildBezierPath(coords))
        }
        apBuilder.append("S Q")

        val bbox = listOf(minX - pad, minY - pad, maxX + pad, maxY + pad)
        val rectArr = COSArray().apply { bbox.forEach { add(COSFloat(it)) } }
        val colorArr = COSArray().apply { rgb.forEach { add(COSFloat(it)) } }
        val bsDict = COSDictionary().apply {
            setName(COSName.TYPE, "Border")
            setName(COSName.SUBTYPE, "S")
            setItem(COSName.getPDFName("W"), COSFloat(strokeWidth))
        }
        val apStream = PDStream(pdDoc)
        apStream.createOutputStream(COSName.FLATE_DECODE).use { it.write(apBuilder.toString().toByteArray()) }
        val apCos = apStream.cosObject.apply {
            setName(COSName.TYPE, "XObject")
            setName(COSName.SUBTYPE, "Form")
            setItem(COSName.BBOX, COSArray().apply { bbox.forEach { add(COSFloat(it)) } })
        }
        val apDict = COSDictionary().apply { setItem(COSName.N, apCos) }

        val nmValue = "ink-" + group.joinToString(",") { it.id.toString() }
        val annDict = COSDictionary().apply {
            setName(COSName.TYPE, "Annot")
            setName(COSName.SUBTYPE, "Ink")
            setItem(COSName.INKLIST, outerList)
            setItem(COSName.RECT, rectArr)
            setItem(COSName.getPDFName("C"), colorArr)
            setItem(COSName.getPDFName("BS"), bsDict)
            setItem(COSName.AP, apDict)
            setString(COSName.NM, nmValue)
        }
        pdPage.annotations.add(PDAnnotationUnknown(annDict))
    }

    // Catmull-Rom spline → cubic Bezier path in PDF content-stream syntax.
    // Returns a string starting with a moveto and followed by curveto operators.
    private fun buildBezierPath(coords: FloatArray): String {
        val n = coords.size / 2
        if (n == 0) return ""
        return buildString {
            append("%.3f %.3f m ".format(coords[0], coords[1]))
            if (n == 1) return@buildString
            if (n == 2) {
                append("%.3f %.3f l ".format(coords[2], coords[3]))
                return@buildString
            }
            for (i in 0 until n - 1) {
                val prev = if (i == 0) i else i - 1
                val next2 = if (i + 2 >= n) n - 1 else i + 2
                val px = coords[i * 2];       val py = coords[i * 2 + 1]
                val qx = coords[(i+1) * 2];   val qy = coords[(i+1) * 2 + 1]
                val ppx = coords[prev * 2];   val ppy = coords[prev * 2 + 1]
                val nnx = coords[next2 * 2];  val nny = coords[next2 * 2 + 1]
                val cp1x = px + (qx - ppx) / 6f; val cp1y = py + (qy - ppy) / 6f
                val cp2x = qx - (nnx - px) / 6f; val cp2y = qy - (nny - py) / 6f
                append("%.3f %.3f %.3f %.3f %.3f %.3f c ".format(cp1x, cp1y, cp2x, cp2y, qx, qy))
            }
        }
    }

    // ---- NM / style helpers ----

    // "ink-42" → [42],  "ink-1,2,3" → [1,2,3],  anything else → null
    private fun parseNmIds(nm: String): List<Int>? {
        if (!nm.startsWith("ink-")) return null
        return nm.removePrefix("ink-").split(",").map { it.trim().toIntOrNull() ?: return null }
    }

    private fun parseColor(cosObj: COSDictionary): StrokeColor {
        val cArr = cosObj.getDictionaryObject(COSName.getPDFName("C")) as? COSArray
        return if (cArr != null && cArr.size() >= 3) {
            val r = (cArr.getObject(0) as? COSNumber)?.floatValue() ?: 0f
            val g = (cArr.getObject(1) as? COSNumber)?.floatValue() ?: 0f
            val b = (cArr.getObject(2) as? COSNumber)?.floatValue() ?: 0f
            StrokeColor.entries.minByOrNull { c ->
                val dr = c.pdfRgb[0]-r; val dg = c.pdfRgb[1]-g; val db = c.pdfRgb[2]-b
                dr*dr + dg*dg + db*db
            } ?: StrokeColor.BLACK
        } else StrokeColor.BLACK
    }

    private fun parseThickness(cosObj: COSDictionary): StrokeThickness {
        val bsDict = cosObj.getDictionaryObject(COSName.getPDFName("BS")) as? COSDictionary
        val w = (bsDict?.getDictionaryObject(COSName.getPDFName("W")) as? COSNumber)?.floatValue() ?: 1.5f
        return StrokeThickness.entries.minByOrNull { kotlin.math.abs(it.multiplier * 1.5f - w) }
            ?: StrokeThickness.MEDIUM
    }

    /**
     * Parses the /AP /N content stream of an Ink annotation and returns the anchor points
     * of each sub-path (one list per InkList inner array). Returns null if the stream is
     * absent or unreadable. For cubic bezier segments only the endpoint is retained —
     * control points are discarded because the renderer re-derives them via Catmull-Rom.
     */
    private fun parseApStrokePoints(annDict: COSDictionary, pageW: Float, pageH: Float): List<List<Offset>>? {
        val apDict = annDict.getDictionaryObject(COSName.AP) as? COSDictionary ?: return null
        val nStream = apDict.getDictionaryObject(COSName.N) as? COSStream ?: return null
        val content = try { nStream.createInputStream().use { String(it.readBytes()) } }
                      catch (e: Exception) { return null }

        val result = mutableListOf<MutableList<Offset>>()
        var current: MutableList<Offset>? = null
        val ops = mutableListOf<Float>()

        for (token in content.trim().split(Regex("\\s+"))) {
            val num = token.toFloatOrNull()
            if (num != null) { ops.add(num); continue }
            when (token) {
                "m" -> if (ops.size >= 2) {
                    current?.let { if (it.isNotEmpty()) result.add(it) }
                    current = mutableListOf(Offset(ops[ops.size - 2] / pageW, 1f - ops[ops.size - 1] / pageH))
                    ops.clear()
                }
                "l" -> if (ops.size >= 2) {
                    current?.add(Offset(ops[ops.size - 2] / pageW, 1f - ops[ops.size - 1] / pageH))
                    ops.clear()
                }
                "c" -> if (ops.size >= 6) {
                    // cubic bezier: cp1x cp1y cp2x cp2y endx endy — keep only endpoint
                    current?.add(Offset(ops[ops.size - 2] / pageW, 1f - ops[ops.size - 1] / pageH))
                    ops.clear()
                }
                "S", "s", "f", "F", "B", "b", "n" -> {
                    current?.let { if (it.isNotEmpty()) result.add(it) }
                    current = null; ops.clear()
                }
                else -> ops.clear() // graphics-state ops (J, j, w, RG, q, Q, …) consume their own operands
            }
        }
        current?.let { if (it.isNotEmpty()) result.add(it) }
        return result.ifEmpty { null }
    }

    // ---- Image Annotation Persistence ----

    /**
     * Replaces all existing image annotations on [pageIndex] with [annotations].
     * Must be called within renderMutex.withLock, with renderer/pfd already closed.
     */
    suspend fun writeImageAnnotationsToPage(uri: Uri, pageIndex: Int, annotations: List<PdfImageAnnotation>) {
        val pdDoc = PDDocument.load(application.contentResolver.openInputStream(uri)!!)
        val pdPage = pdDoc.getPage(pageIndex)
        // Remove old image annotations on this page (collect first, then remove as a batch
        // so the COSArrayList propagates the deletion to the underlying COSArray correctly)
        val pageAnnotations = pdPage.annotations
        val toRemove = pageAnnotations.filter { ann ->
            ann.getCOSObject().getNameAsString(COSName.SUBTYPE) == "Stamp" &&
            (ann.annotationName ?: "").startsWith("img-")
        }
        pageAnnotations.removeAll(toRemove)
        // Add current set
        annotations.forEach { addImageAnnotationToPage(pdDoc, pdPage, it) }
        pdfRepository.save(pdDoc, uri)
        pdDoc.close()
    }

    private fun addImageAnnotationToPage(pdDoc: PDDocument, pdPage: PDPage, annot: PdfImageAnnotation) {
        val mbox = pdPage.mediaBox
        val r = annot.rectNorm
        val pdfX = r.left  * mbox.width
        val pdfY = (1f - r.bottom) * mbox.height   // PDF y=0 at bottom
        val pdfW = (r.right  - r.left)   * mbox.width
        val pdfH = (r.bottom - r.top)    * mbox.height

        // Create Image XObject via JPEGFactory (handles DCT encoding internally)
        val pdImage = JPEGFactory.createFromImage(pdDoc, annot.bitmap)
        val imgCos = pdImage.cosObject

        // Build Form XObject AP stream: q <w> 0 0 <h> 0 0 cm /Img Do Q
        val apContent = "q %.3f 0 0 %.3f 0 0 cm /Img Do Q".format(pdfW, pdfH)
        val xobjDict = COSDictionary().apply { setItem(COSName.getPDFName("Img"), imgCos) }
        val resourcesDict = COSDictionary().apply { setItem(COSName.XOBJECT, xobjDict) }
        val bboxArr = COSArray().apply {
            add(COSFloat(0f)); add(COSFloat(0f)); add(COSFloat(pdfW)); add(COSFloat(pdfH))
        }
        val apFormDict = COSDictionary().apply {
            setName(COSName.TYPE, "XObject"); setName(COSName.SUBTYPE, "Form")
            setItem(COSName.BBOX, bboxArr); setItem(COSName.RESOURCES, resourcesDict)
        }
        val apStream = PDStream(pdDoc)
        apStream.createOutputStream(COSName.FLATE_DECODE).use { it.write(apContent.toByteArray()) }
        apStream.cosObject.addAll(apFormDict)

        // Build annotation dictionary
        val rectArr = COSArray().apply {
            add(COSFloat(pdfX)); add(COSFloat(pdfY))
            add(COSFloat(pdfX + pdfW)); add(COSFloat(pdfY + pdfH))
        }
        val apDict = COSDictionary().apply {
            setItem(COSName.getPDFName("N"), apStream.cosObject)
        }
        val annotDict = COSDictionary().apply {
            setName(COSName.TYPE, "Annot"); setName(COSName.SUBTYPE, "Stamp")
            setString(COSName.NM, "img-${annot.id}")
            setItem(COSName.RECT, rectArr)
            setItem(COSName.AP, apDict)
        }
        pdPage.annotations.add(PDAnnotationUnknown(annotDict))
    }

    /**
     * Extracts image annotations from [pageIndex] in [pdDoc].
     * Returns normalized rects (y=0 at top, 0-1 coords).
     */
    fun extractImageAnnotations(pdDoc: PDDocument, pageIndex: Int, pageW: Float, pageH: Float): List<PdfImageAnnotation> {
        return try {
            val pdPage = pdDoc.getPage(pageIndex)
            val mbox = pdPage.mediaBox
            val result = mutableListOf<PdfImageAnnotation>()
            for (ann in pdPage.annotations) {
                if (ann.getCOSObject().getNameAsString(COSName.SUBTYPE) != "Stamp") continue
                val nm = ann.annotationName ?: continue
                if (!nm.startsWith("img-")) continue
                val id = nm.removePrefix("img-").toIntOrNull() ?: continue

                // Get rect
                val rectCos = ann.getCOSObject().getDictionaryObject(COSName.RECT) as? COSArray ?: continue
                val pdfX1 = (rectCos.getObject(0) as? COSNumber)?.floatValue() ?: continue
                val pdfY1 = (rectCos.getObject(1) as? COSNumber)?.floatValue() ?: continue
                val pdfX2 = (rectCos.getObject(2) as? COSNumber)?.floatValue() ?: continue
                val pdfY2 = (rectCos.getObject(3) as? COSNumber)?.floatValue() ?: continue

                // Extract JPEG bytes from AP stream → Image XObject
                val apDict = ann.getCOSObject().getDictionaryObject(COSName.AP) as? COSDictionary ?: continue
                val apForm = apDict.getDictionaryObject(COSName.getPDFName("N")) as? COSStream ?: continue
                val resDict = apForm.getDictionaryObject(COSName.RESOURCES) as? COSDictionary ?: continue
                val xobjDict = resDict.getDictionaryObject(COSName.XOBJECT) as? COSDictionary ?: continue
                val imgStream = xobjDict.getDictionaryObject(COSName.getPDFName("Img")) as? COSStream ?: continue
                val jpegBytes = imgStream.createRawInputStream().use { it.readBytes() }
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: continue

                // Convert PDF coords (y=0 at bottom) to normalized (y=0 at top)
                val normLeft   = pdfX1 / mbox.width
                val normBottom = pdfY1 / mbox.height
                val normRight  = pdfX2 / mbox.width
                val normTop    = pdfY2 / mbox.height
                val rectNorm = RectF(normLeft, 1f - normTop, normRight, 1f - normBottom)
                result.add(PdfImageAnnotation(id = id, bitmap = bitmap, rectNorm = rectNorm))
            }
            result
        } catch (e: Exception) {
            emptyList()
        }
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
