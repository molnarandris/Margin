package io.github.molnarandris.margin.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PdfMetadataDao {
    @Upsert
    suspend fun upsert(entity: PdfMetadataEntity)

    @Query("SELECT * FROM pdf_metadata WHERE uri = :uri")
    suspend fun getByUri(uri: String): PdfMetadataEntity?

    @Query("DELETE FROM pdf_metadata WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
