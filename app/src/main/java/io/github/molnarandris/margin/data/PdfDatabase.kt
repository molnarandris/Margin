package io.github.molnarandris.margin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [PdfMetadataEntity::class], version = 1)
abstract class PdfDatabase : RoomDatabase() {
    abstract fun pdfMetadataDao(): PdfMetadataDao

    companion object {
        @Volatile private var INSTANCE: PdfDatabase? = null

        fun getInstance(context: Context): PdfDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PdfDatabase::class.java,
                    "pdf_metadata.db"
                ).build().also { INSTANCE = it }
            }
    }
}
