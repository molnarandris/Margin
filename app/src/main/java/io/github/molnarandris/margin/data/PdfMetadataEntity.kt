package io.github.molnarandris.margin.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pdf_metadata")
data class PdfMetadataEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val title: String,
    val author: String,
    val lastModified: Long
)
