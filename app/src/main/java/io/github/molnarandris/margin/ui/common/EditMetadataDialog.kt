package io.github.molnarandris.margin.ui.common

import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@Composable
fun EditMetadataDialog(
    title: String,
    authors: List<String>,
    people: List<String>,
    createdAt: Long,
    fileUri: Uri,
    rootUri: Uri?,
    onSave: (title: String, authors: List<String>, people: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var titleText by rememberSaveable(title) { mutableStateOf(title) }
    var authorText by rememberSaveable(authors) { mutableStateOf(authors.joinToString(", ")) }
    var peopleText by rememberSaveable(people) { mutableStateOf(people.joinToString(", ")) }
    var fileSize by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(fileUri) {
        fileSize = withContext(Dispatchers.IO) {
            context.contentResolver.query(fileUri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
        }
    }

    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface
    val cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit metadata") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Title", style = labelStyle, color = labelColor)
                Spacer(Modifier.height(2.dp))
                BasicTextField(
                    value = titleText,
                    onValueChange = { titleText = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                    cursorBrush = cursorBrush,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Authors", style = labelStyle, color = labelColor)
                Spacer(Modifier.height(2.dp))
                BasicTextField(
                    value = authorText,
                    onValueChange = { authorText = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                    cursorBrush = cursorBrush,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (authorText.isEmpty()) {
                            Text(
                                "Separate with commas",
                                style = MaterialTheme.typography.bodyMedium,
                                color = labelColor
                            )
                        }
                        innerTextField()
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text("People", style = labelStyle, color = labelColor)
                Spacer(Modifier.height(2.dp))
                BasicTextField(
                    value = peopleText,
                    onValueChange = { peopleText = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                    cursorBrush = cursorBrush,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (peopleText.isEmpty()) {
                            Text(
                                "Separate with commas",
                                style = MaterialTheme.typography.bodyMedium,
                                color = labelColor
                            )
                        }
                        innerTextField()
                    }
                )
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                InfoRow("Created", formatCreatedAt(createdAt))
                InfoRow("Location", resolveRelativePath(fileUri, rootUri))
                InfoRow("Size", formatFileSize(fileSize))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsedAuthors = authorText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                val parsedPeople = peopleText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                onSave(titleText.trim(), parsedAuthors, parsedPeople)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.28f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.72f)
        )
    }
    Spacer(Modifier.height(4.dp))
}

private fun formatCreatedAt(millis: Long): String {
    if (millis == 0L) return "—"
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    val year = cal.get(Calendar.YEAR)
    val month = "%02d".format(cal.get(Calendar.MONTH) + 1)
    val day = "%02d".format(cal.get(Calendar.DAY_OF_MONTH))
    val hour = "%02d".format(cal.get(Calendar.HOUR_OF_DAY))
    val minute = if (cal.get(Calendar.MINUTE) >= 30) "30" else "00"
    return "$year.$month.$day as $hour:$minute"
}

private fun formatFileSize(bytes: Long?): String {
    if (bytes == null) return "—"
    return when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }
}

private fun resolveRelativePath(fileUri: Uri, rootUri: Uri?): String {
    if (rootUri == null) return fileUri.lastPathSegment ?: fileUri.toString()
    return try {
        val docId = DocumentsContract.getDocumentId(fileUri)
        val treeId = DocumentsContract.getTreeDocumentId(rootUri)
        if (docId.startsWith("$treeId/")) docId.removePrefix("$treeId/")
        else fileUri.lastPathSegment ?: docId
    } catch (_: Exception) {
        fileUri.lastPathSegment ?: fileUri.toString()
    }
}
