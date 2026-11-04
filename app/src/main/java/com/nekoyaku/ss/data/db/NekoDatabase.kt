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
    version = 1,
    exportSchema = false,
)
abstract class NekoDatabase : RoomDatabase() {
    abstract fun dao(): NekoDao

    companion object {
        fun build(context: Context): NekoDatabase =
            Room.databaseBuilder(context, NekoDatabase::class.java, "nekoyaku.db").build()
    }
}
