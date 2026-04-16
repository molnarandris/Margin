package io.github.molnarandris.margin.ui.pdfviewer

import android.graphics.RectF

sealed class UndoableAction {
    data class StrokeAdded(val pageIndex: Int, val stroke: InkStroke) : UndoableAction()
    data class StrokesErased(val pageIndex: Int, val strokes: List<InkStroke>) : UndoableAction()
    data class PageJumped(val fromPage: Int, val toPage: Int) : UndoableAction()
    // Highlights identified by bounds (not annotationIndex, which can drift after add/delete)
    data class HighlightAdded(val pageIndex: Int, val bounds: List<RectF>, val note: String?) : UndoableAction()
    data class HighlightDeleted(val pageIndex: Int, val bounds: List<RectF>, val note: String?) : UndoableAction()
    data class AnnotationEdited(val pageIndex: Int, val bounds: List<RectF>, val oldNote: String?, val newNote: String?) : UndoableAction()
    data class MetadataChanged(val oldTitle: String, val newTitle: String, val oldAuthors: List<String>, val newAuthors: List<String>, val oldProjects: List<String>, val newProjects: List<String>) : UndoableAction()
    data class StrokesMoved(val pageIndex: Int, val originalStrokes: List<InkStroke>, val movedStrokes: List<InkStroke>) : UndoableAction()
    data class ImageAnnotationAdded(val pageIndex: Int, val annotation: PdfImageAnnotation) : UndoableAction()
    data class ImageAnnotationDeleted(val pageIndex: Int, val annotation: PdfImageAnnotation) : UndoableAction()
    data class ImageAnnotationTransformed(val pageIndex: Int, val old: PdfImageAnnotation, val new: PdfImageAnnotation) : UndoableAction()
}
