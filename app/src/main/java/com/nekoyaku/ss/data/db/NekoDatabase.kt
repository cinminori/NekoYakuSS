package com.nekoyaku.ss.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * NekoYakuSS 的資料庫：段落快取（translation）與名詞表（glossary）等。
 */
@Database(
    entities = [
        TranslationEntity::class,
        BookEntity::class,
        GlossaryEntity::class,
        ChapterBookEntity::class,
    ],
    version = NekoDatabase.SCHEMA_VERSION,
    exportSchema = false,
)
abstract class NekoDatabase : RoomDatabase() {
    abstract fun dao(): NekoDao

    companion object {
        /** 備份還原也用這個判斷版本相容：改 schema 時 bump 這裡。 */
        const val SCHEMA_VERSION = 1
        const val DB_NAME = "nekoyaku.db"

        fun build(context: Context): NekoDatabase =
            Room.databaseBuilder(context, NekoDatabase::class.java, DB_NAME).build()
    }
}
