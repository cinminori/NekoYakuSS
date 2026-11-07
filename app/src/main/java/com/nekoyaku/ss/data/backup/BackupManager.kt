package com.nekoyaku.ss.data.backup

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import com.nekoyaku.ss.BuildConfig
import com.nekoyaku.ss.data.db.NekoDatabase
import com.nekoyaku.ss.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 備份／還原：手機壞掉、誤刪或換機用。`allowBackup=false`（API key、cookie 不上雲端）的代價是
 * 解除安裝＝歸零，這裡補上自己的路：把私有資料打成一個 zip，存到使用者選的位置。
 *
 * zip 佈局（manifest 一定是第一個 entry，還原前只讀它就能出確認框）：
 *   manifest.json
 *   nekoyaku.db                 Room 快照（譯文快取、單處修改、書架、名詞表）
 *   prefs/overlay_settings.json
 *   prefs/ai_profiles.json      可選：含 API key
 *   font/tr-font                有匯入自訂字型才有
 *
 * 不帶網站登入狀態：cookie 在 WebView 自己的目錄裡，搬了也不保證有效，還原後重新登入。
 *
 * 還原採「下次啟動前替換」：Room 是 Application 層 lazy 單例，開著換檔會有競態。還原只把內容解到
 * `files/restore_pending/`、寫 READY 標記、重啟 process；[applyPendingRestore] 在
 * [com.nekoyaku.ss.NekoYakuApp.onCreate] 最前面（任何人碰 DB／prefs 之前）搬到正位。
 */
object BackupManager {
    private const val APP_ID = "NekoYakuSS"
    private const val FORMAT = 1
    private const val TAG = "backup"
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val DB_NAME = NekoDatabase.DB_NAME
    private val REQUIRED_TABLES = setOf("translation", "book", "glossary", "chapter_book")
    private const val PREFS_OVERLAY = "overlay_settings"
    private const val PREFS_AI = "ai_profiles"
    private const val ENTRY_PREFS_OVERLAY = "prefs/$PREFS_OVERLAY.json"
    private const val ENTRY_PREFS_AI = "prefs/$PREFS_AI.json"
    private const val FONT_FILE = "tr-font"
    private const val ENTRY_FONT = "font/$FONT_FILE"
    private const val PENDING_DIR = "restore_pending"
    private const val READY_MARKER = "READY"
    private const val BUF = 256 * 1024

    /** 還原只收這些檔名：備份檔可能來自別人，不讓它寫到其他位置。 */
    private val ALLOWED_ENTRIES = setOf(ENTRY_MANIFEST, DB_NAME, ENTRY_PREFS_OVERLAY, ENTRY_PREFS_AI, ENTRY_FONT)
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class Manifest(
        val app: String = "",
        val format: Int = FORMAT,
        val appVersion: String = "",
        val dbVersion: Int = 0,
        val createdAt: Long = 0,
        val device: String = "",
        val includesAiProfiles: Boolean = false,
        val translations: Int = 0,
        val books: Int = 0,
        val glossary: Int = 0,
        /** 內容總位元組，還原端算進度用。 */
        val totalBytes: Long = 0,
    )

    /** 進度回呼：phase 是給人看的短句；done／total 為位元組，total 0＝還不知道。IO 執行緒呼叫。 */
    fun interface Progress {
        fun report(phase: String, done: Long, total: Long)
    }

    /** 目前資料約多大（磁碟實際大小，不是壓縮後）。 */
    suspend fun estimateBytes(ctx: Context): Long = withContext(Dispatchers.IO) {
        val db = ctx.getDatabasePath(DB_NAME)
        db.length() + File(db.path + "-wal").length() + File(ctx.filesDir, FONT_FILE).length()
    }

    // ───────────────────────── 備份 ─────────────────────────

    /**
     * 打包到 [uri]（SAF CreateDocument 拿到的）。回傳內容總位元組（未壓縮）。
     * 中途取消會留下半截檔，呼叫端要用不會因離開畫面就取消的 scope。
     */
    suspend fun backup(ctx: Context, db: NekoDatabase, uri: Uri, includeAiProfiles: Boolean, progress: Progress): Long =
        withContext(Dispatchers.IO) {
            val p = Throttled(progress)
            val tmpDir = File(ctx.cacheDir, "backup_tmp").apply { deleteRecursively(); mkdirs() }
            try {
                p.report("正在製作資料庫快照…", 0, 0)
                val snap = File(tmpDir, DB_NAME)
                snapshotDatabase(db, snap)
                val font = File(ctx.filesDir, FONT_FILE).takeIf { it.isFile }
                val total = snap.length() + (font?.length() ?: 0)
                val dao = db.dao()
                val manifest = Manifest(
                    app = APP_ID,
                    appVersion = BuildConfig.VERSION_NAME,
                    dbVersion = NekoDatabase.SCHEMA_VERSION,
                    createdAt = System.currentTimeMillis(),
                    device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                    includesAiProfiles = includeAiProfiles,
                    translations = dao.countTranslations(),
                    books = dao.countBooks(),
                    glossary = dao.countGlossary(),
                    totalBytes = total,
                )
                var written = 0L
                ZipOutputStream(openForWrite(ctx, uri).buffered(BUF)).use { zip ->
                    // e-ink CPU 弱、DB 是文字本來就好壓：速度優先
                    zip.setLevel(Deflater.BEST_SPEED)
                    putBytes(zip, ENTRY_MANIFEST, json.encodeToString(manifest).toByteArray())
                    putFile(zip, DB_NAME, snap) { n ->
                        written += n
                        p.report("正在寫入資料庫…", written, total)
                    }
                    font?.let {
                        putFile(zip, ENTRY_FONT, it) { n ->
                            written += n
                            p.report("正在寫入字型…", written, total)
                        }
                    }
                    putBytes(zip, ENTRY_PREFS_OVERLAY, prefsToJson(ctx.getSharedPreferences(PREFS_OVERLAY, Context.MODE_PRIVATE)).toByteArray())
                    if (includeAiProfiles) {
                        putBytes(zip, ENTRY_PREFS_AI, prefsToJson(ctx.getSharedPreferences(PREFS_AI, Context.MODE_PRIVATE)).toByteArray())
                    }
                }
                p.report("完成", total, total)
                DebugLog.i(TAG, "備份完成 ${total}B 譯文=${manifest.translations} 含 key=$includeAiProfiles")
                total
            } finally {
                tmpDir.deleteRecursively()
            }
        }

    /** Room 預設 WAL，直接複製主檔會少掉還沒 checkpoint 的寫入。API 29+ 用 `VACUUM INTO` 取一致快照。 */
    private fun snapshotDatabase(db: NekoDatabase, dst: File) {
        val sdb = db.openHelper.writableDatabase
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            sdb.execSQL("VACUUM INTO '${dst.absolutePath.replace("'", "''")}'")
        } else {
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            File(requireNotNull(sdb.path) { "資料庫路徑不明" }).copyTo(dst, overwrite = true)
        }
        check(dst.length() > 0) { "資料庫快照為空" }
    }

    /** "wt" 才會截斷：覆蓋既有檔時，"w" 在部分 provider 不截斷，尾巴殘留舊資料會讓 zip 壞掉。 */
    private fun openForWrite(ctx: Context, uri: Uri): OutputStream =
        runCatching { ctx.contentResolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: ctx.contentResolver.openOutputStream(uri, "w")
            ?: error("無法寫入所選位置")

    private fun putBytes(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, name: String, f: File, onChunk: (Int) -> Unit) {
        zip.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
        f.inputStream().buffered(BUF).use { copy(it, zip, onChunk) }
        zip.closeEntry()
    }

    private fun copy(input: InputStream, out: OutputStream, onChunk: (Int) -> Unit) {
        val buf = ByteArray(BUF)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            onChunk(n)
        }
    }

    // ───────────────────────── 還原 ─────────────────────────

    /** 只讀 zip 第一個 entry（manifest），出確認框用。不是本 App 的備份就丟例外。 */
    suspend fun peek(ctx: Context, uri: Uri): Manifest = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: error("無法讀取所選檔案")
        input.use { raw ->
            ZipInputStream(raw.buffered(BUF)).use { zip ->
                val e = zip.nextEntry ?: error("這不是 zip 檔")
                if (e.name != ENTRY_MANIFEST) error("這不是 NekoYakuSS 備份檔")
                val m = runCatching { json.decodeFromString<Manifest>(zip.readBytes().toString(Charsets.UTF_8)) }
                    .getOrElse { error("這不是 NekoYakuSS 備份檔") }
                if (m.app != APP_ID) error("這不是 NekoYakuSS 備份檔")
                m
            }
        }
    }

    /** 這份備份能不能在這個版本還原；能→null，不能→給人看的原因。 */
    fun compatibilityError(m: Manifest): String? =
        if (m.format > FORMAT || m.dbVersion > NekoDatabase.SCHEMA_VERSION) {
            "這個備份來自較新的 NekoYakuSS ${m.appVersion}，請先更新 App 再還原。"
        } else {
            null
        }

    /** 解到 `files/restore_pending/`，最後寫 READY 標記。不動現行資料；失敗就清掉，不留半套。 */
    suspend fun stageRestore(ctx: Context, uri: Uri, manifest: Manifest, progress: Progress) = withContext(Dispatchers.IO) {
        val p = Throttled(progress)
        val pending = File(ctx.filesDir, PENDING_DIR).apply { deleteRecursively(); mkdirs() }
        try {
            var done = 0L
            val total = manifest.totalBytes
            val input = ctx.contentResolver.openInputStream(uri) ?: error("無法讀取所選檔案")
            input.use { raw ->
                ZipInputStream(raw.buffered(BUF)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        coroutineContext.ensureActive()
                        if (e.name in ALLOWED_ENTRIES) {
                            val dst = File(pending, e.name)
                            dst.parentFile?.mkdirs()
                            dst.outputStream().buffered(BUF).use { out ->
                                copy(zip, out) { n ->
                                    done += n
                                    p.report("正在解出備份…", done, total)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            val stagedDb = File(pending, DB_NAME)
            check(isSqlite(stagedDb)) { "備份檔裡的資料庫已損毀" }
            check(isIntactDb(stagedDb)) { "備份檔裡的資料庫不完整或已損毀，沒有還原" }
            File(pending, READY_MARKER).writeText(manifest.createdAt.toString())
            p.report("完成", total, total)
        } catch (t: Throwable) {
            pending.deleteRecursively()
            throw t
        }
    }

    private fun isSqlite(f: File): Boolean {
        if (f.length() < SQLITE_HEADER.size) return false
        val head = ByteArray(SQLITE_HEADER.size)
        f.inputStream().use { it.read(head) }
        return head.contentEquals(SQLITE_HEADER)
    }

    /**
     * 檔頭之外再確認整個資料庫讀得通、該有的表都在：寫到一半的備份檔頭也完好，
     * 不擋下來的話還原後 Room 開庫失敗、原本的資料又已被換掉，每次開 App 都閃退。
     */
    private fun isIntactDb(f: File): Boolean = runCatching {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
            val ok = db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            ok && REQUIRED_TABLES.all { it in tables }
        }
    }.getOrDefault(false)

    /**
     * 啟動時套用待還原資料。必須在任何人碰 DB／SharedPreferences 之前呼叫（Application.onCreate 第一行）。
     * 順序：DB（先把現行挪成 .bak、失敗搬回）→ 字型（整個取代）→ prefs（備份有帶的才整份寫回）。
     */
    fun applyPendingRestore(ctx: Context) {
        val pending = File(ctx.filesDir, PENDING_DIR)
        if (!File(pending, READY_MARKER).exists()) {
            if (pending.exists()) pending.deleteRecursively() // 上次解到一半被殺：清掉，不套半套
            return
        }
        val dbFile = ctx.getDatabasePath(DB_NAME)
        dbFile.parentFile?.mkdirs()
        val bak = File(dbFile.path + ".bak").also { it.delete() }
        listOf("-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        if (dbFile.exists() && !dbFile.renameTo(bak)) {
            DebugLog.e(TAG, "現行資料庫挪不開，放棄還原")
            pending.deleteRecursively()
            return
        }
        val stagedDb = File(pending, DB_NAME)
        val moved = stagedDb.renameTo(dbFile) || runCatching { stagedDb.copyTo(dbFile, overwrite = true); true }.getOrDefault(false)
        if (!moved) {
            DebugLog.e(TAG, "搬資料庫失敗，回復原庫")
            dbFile.delete()
            bak.renameTo(dbFile)
            pending.deleteRecursively()
            return
        }
        bak.delete()

        val font = File(ctx.filesDir, FONT_FILE)
        font.delete()
        File(pending, ENTRY_FONT).takeIf { it.isFile }?.let { staged ->
            if (!staged.renameTo(font)) staged.copyTo(font, overwrite = true)
        }

        listOf(PREFS_OVERLAY to ENTRY_PREFS_OVERLAY, PREFS_AI to ENTRY_PREFS_AI).forEach { (name, entry) ->
            val f = File(pending, entry)
            if (f.isFile) {
                runCatching { jsonToPrefs(f.readText(), ctx.getSharedPreferences(name, Context.MODE_PRIVATE)) }
                    .onFailure { DebugLog.e(TAG, "還原設定 $name 失敗", it) }
            }
        }
        pending.deleteRecursively()
        DebugLog.i(TAG, "還原完成 db=${dbFile.length()}B")
    }

    /**
     * 重啟 process 讓 [applyPendingRestore] 跑。極少數 ROM 會連新 activity 一起收掉，
     * 那也只是沒自動重開：待還原資料還在，手動再開一次照樣套用（畫面文案有講）。
     */
    fun relaunch(ctx: Context) {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    // ───────────────────────── prefs ⇄ JSON ─────────────────────────

    /** 帶型別標記：JSON 數字分不出 Int／Long／Float，還原時 `getFloat` 撞到 Int 會 ClassCastException。 */
    private fun prefsToJson(prefs: SharedPreferences): String {
        val entries = prefs.all.mapNotNull { (k, v) -> typed(v)?.let { (t, jv) -> Triple(k, t, jv) } }
        return buildJsonObject {
            for ((k, t, jv) in entries) {
                put(k, buildJsonObject { put("t", t); put("v", jv) })
            }
        }.toString()
    }

    private fun typed(v: Any?): Pair<String, JsonElement>? = when (v) {
        is String -> "string" to JsonPrimitive(v)
        is Boolean -> "boolean" to JsonPrimitive(v)
        is Int -> "int" to JsonPrimitive(v)
        is Long -> "long" to JsonPrimitive(v)
        is Float -> "float" to JsonPrimitive(v)
        is Set<*> -> "set" to JsonArray(v.filterIsInstance<String>().map { JsonPrimitive(it) })
        else -> null
    }

    private fun jsonToPrefs(text: String, prefs: SharedPreferences) {
        val obj = json.parseToJsonElement(text).jsonObject
        val ed = prefs.edit().clear()
        for ((k, e) in obj) {
            val o = e.jsonObject
            val v = o["v"] ?: continue
            when (o["t"]?.jsonPrimitive?.content) {
                "string" -> ed.putString(k, v.jsonPrimitive.content)
                "boolean" -> ed.putBoolean(k, v.jsonPrimitive.boolean)
                "int" -> ed.putInt(k, v.jsonPrimitive.int)
                "long" -> ed.putLong(k, v.jsonPrimitive.long)
                "float" -> ed.putFloat(k, v.jsonPrimitive.float)
                "set" -> ed.putStringSet(k, v.jsonArray.map { it.jsonPrimitive.content }.toSet())
            }
        }
        ed.commit() // 啟動路徑上要同步落盤，之後 lazy 建的 store 才讀得到
    }

    /** 進度節流：太頻繁會讓 e-ink 整頁狂刷。只在換階段、跑完、或（≥300ms 且前進 ≥1%）才往上報。 */
    private class Throttled(private val inner: Progress) : Progress {
        private var lastPhase = ""
        private var lastAt = 0L
        private var lastDone = 0L

        override fun report(phase: String, done: Long, total: Long) {
            val now = System.currentTimeMillis()
            val finished = total > 0 && done >= total
            val moved = total <= 0 || done - lastDone >= total / 100
            if (phase != lastPhase || finished || (now - lastAt >= 300 && moved)) {
                lastPhase = phase
                lastAt = now
                lastDone = done
                inner.report(phase, done, total)
            }
        }
    }
}
