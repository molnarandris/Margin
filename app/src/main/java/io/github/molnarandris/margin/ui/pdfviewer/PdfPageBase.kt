package io.github.molnarandris.margin.ui.pdfviewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun PdfPageBase(
    page: PdfPage,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier) {
        Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = "Page",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth()
        )
        content()
        if (page.isNotePage) Canvas(modifier = Modifier.matchParentSize()) {
            // 6 mm grid; PDF is 72 pts/inch → 1 mm = 72/25.4 pts
            val stepPx = 6f * 72f / 25.4f * size.width / page.nativeWidth
            val lineColor = Color(0x2200A8FF)
            val strokePx = 1.dp.toPx()
            val offsetX = (size.width  % stepPx) / 2f
            val offsetY = (size.height % stepPx) / 2f
            var x = offsetX
            while (x < size.width) {
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = strokePx)
                x += stepPx
            }
            var y = offsetY
            while (y < size.height) {
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = strokePx)
                y += stepPx
            }
        }
    }
}
