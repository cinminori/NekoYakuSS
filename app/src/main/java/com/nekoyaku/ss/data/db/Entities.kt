package com.nekoyaku.ss.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 段落譯文快取。key = "siteId|bookKey|chapterKey|段落hash"（hash＝段落原文的 `String.hashCode()`）。
 *
 * NekoYakuSS **只存譯文、不存原文**：原文永遠留在原網站上，下次打開同一話時，
 * 注入腳本把網頁上的段落文字送回來，算出同樣的 hash 就直接套用譯文。
 */
@Entity(tableName = "translation")
data class TranslationEntity(
    @PrimaryKey val key: String,
    val text: String,
)

/**
 * 書架上的一本書＝作品＋名詞表＋閱讀紀錄（上次讀到的那一話的網址）。
 * 點書直接在站內瀏覽器開到 [lastUrl]。
 */
@Entity(tableName = "book", primaryKeys = ["siteId", "bookKey"])
data class BookEntity(
    val siteId: String,
    val bookKey: String,
    /** 原站書名（從網頁標題推出來的；使用者可改）。 */
    val bookTitle: String = "",
    /** 中文譯名：書架優先顯示。空＝還沒翻／沒填。 */
    val aliasZh: String = "",
    /** 本書翻譯提示詞（題材背景、用語領域），注入翻譯與名詞抽取的 prompt。 */
    val promptNote: String = "",
    /** 名詞表共用：非空＝本書名詞表指向該 bookId（"siteId|bookKey"）。 */
    val glossaryShareId: String = "",
    /** 上次讀到的那一話網址（點書就開這裡）。 */
    val lastUrl: String = "",
    val lastChapterKey: String = "",
    val lastChapterTitle: String = "",
    val updatedAt: Long = 0,
)

/**
 * 名詞表：每本書一份（bookId = "siteId|bookKey"）。韓文原詞 → 中譯。
 * confirmed=true 為使用者確認的正式名詞（翻譯時注入 prompt）；false 為 AI 抽取的候選。
 */
@Entity(tableName = "glossary", primaryKeys = ["bookId", "term"])
data class GlossaryEntity(
    val bookId: String,
    val term: String,
    val translation: String,
    val note: String,
    val confirmed: Boolean,
    val type: String,
    val updatedAt: Long,
)

/**
 * 章節 → 書的對照（ridi 專用）：ridi 每一話是一個獨立的 bookId，網址上看不出屬於哪個系列，
 * 要抓一次詳情頁才知道「系列首話」是誰。查過就記下來，下次開同一話不必再抓。
 */
@Entity(tableName = "chapter_book", primaryKeys = ["siteId", "chapterKey"])
data class ChapterBookEntity(
    val siteId: String,
    val chapterKey: String,
    val bookKey: String,
)
