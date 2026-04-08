package io.github.molnarandris.margin.ui.pdfviewer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

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
    }
}
