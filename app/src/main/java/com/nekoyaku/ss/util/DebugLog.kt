package com.nekoyaku.ss.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全 app 共用的 in-memory debug log。
 *
 * 為什麼存在：使用者多數時間離電腦，沒辦法 logcat。所以把關鍵診斷訊息（抽文段數、翻譯批次、
 * HTTP code、重試、錯誤）丟進這個 ring buffer，使用者在 app 內按「除錯紀錄」就能複製貼回來。
 *
 * 同時也會 echo 到 logcat（tag = "NekoYakuSS/…"），開電腦時還是看得到。
 */
object DebugLog {
    data class Entry(val ts: Long, val level: String, val tag: String, val message: String)

    private const val MAX = 400
    private val buffer = ArrayDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // logcat echo 包 runCatching：JVM 單元測試沒有 android.util.Log（stub 直接拋），
    // ring buffer 本體照記——讓走到 DebugLog 的解析路徑（adapter parse 等）可以被單元測試
    fun d(tag: String, msg: String) = add("D", tag, msg).also { runCatching { android.util.Log.d("NekoYakuSS/$tag", msg) } }
    fun i(tag: String, msg: String) = add("I", tag, msg).also { runCatching { android.util.Log.i("NekoYakuSS/$tag", msg) } }
    fun w(tag: String, msg: String) = add("W", tag, msg).also { runCatching { android.util.Log.w("NekoYakuSS/$tag", msg) } }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        val full = if (t == null) msg else "$msg | ${t.javaClass.simpleName}: ${t.message}"
        add("E", tag, full)
        runCatching { android.util.Log.e("NekoYakuSS/$tag", msg, t) }
    }

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, tag, msg)
        buffer.addLast(e)
        while (buffer.size > MAX) buffer.removeFirst()
        _entries.value = buffer.toList()
    }

    @Synchronized
    fun clear() {
        buffer.clear()
        _entries.value = emptyList()
    }

    /** 把目前 buffer 序列化成可複製的純文字（每行：HH:mm:ss.SSS [L] tag | msg）。 */
    fun snapshotText(): String {
        val snapshot = entries.value
        return buildString {
            appendLine("# NekoYakuSS Debug Log（${snapshot.size} 筆）")
            appendLine()
            snapshot.forEach { e ->
                append(timeFmt.format(Date(e.ts)))
                append(' '); append('['); append(e.level); append("] ")
                append(e.tag); append(" | "); appendLine(e.message)
            }
        }
    }
}
