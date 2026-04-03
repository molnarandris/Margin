package io.github.molnarandris.margin.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PdfMetadataEntity::class], version = 3)
abstract class PdfDatabase : RoomDatabase() {
    abstract fun pdfMetadataDao(): PdfMetadataDao

    companion object {
        @Volatile private var INSTANCE: PdfDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pdf_metadata ADD COLUMN type TEXT NOT NULL DEFAULT 'DOCUMENT'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE pdf_metadata ADD COLUMN projects TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): PdfDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PdfDatabase::class.java,
                    "pdf_metadata.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { INSTANCE = it }
            }
    }
}
