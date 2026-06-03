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

    @Query("SELECT * FROM pdf_metadata")
    suspend fun getAll(): List<PdfMetadataEntity>

    @Query("SELECT author FROM pdf_metadata WHERE author != ''")
    suspend fun getAllAuthors(): List<String>

    @Query("SELECT people FROM pdf_metadata WHERE people != ''")
    suspend fun getAllPeople(): List<String>

    @Query("UPDATE pdf_metadata SET lastOpened = :lastOpened WHERE uri = :uri")
    suspend fun updateLastOpened(uri: String, lastOpened: Long)
}
