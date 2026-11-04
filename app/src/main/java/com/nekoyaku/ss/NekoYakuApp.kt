package com.nekoyaku.ss

import android.app.Application
import android.os.Build
import com.nekoyaku.ss.ai.AiProfileStore
import com.nekoyaku.ss.ai.TranslationService
import com.nekoyaku.ss.data.db.CacheRepository
import com.nekoyaku.ss.data.db.GlossaryRepository
import com.nekoyaku.ss.data.db.NekoDatabase
import com.nekoyaku.ss.overlay.OverlaySettings
import com.nekoyaku.ss.util.DebugLog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** 簡易 DI：以 Application 持有單例。 */
class NekoYakuApp : Application() {
    val aiProfileStore by lazy { AiProfileStore(this) }
    val translationService by lazy { TranslationService() }
    val overlaySettings by lazy { OverlaySettings(this) }
    val database by lazy { NekoDatabase.build(this) }
    val cacheRepository by lazy { CacheRepository(database.dao()) }
    val glossaryRepository by lazy { GlossaryRepository(database.dao()) }

    /** 網站介面翻譯的記憶體快取（原文 → 譯文）；選單、按鈕字串跨頁重複很多。 */
    val uiTextCache = HashMap<String, String>()

    /** 站內瀏覽器正在顯示章節頁（MainActivity 依此決定要不要把音量鍵當翻頁）。 */
    val isReading = MutableStateFlow(false)

    /** 音量鍵翻頁信號：+1＝下一頁、-1＝上一頁。 */
    val pageScrollRequest = MutableSharedFlow<Int>(extraBufferCapacity = 4)

    override fun onCreate() {
        super.onCreate()
        // 必須先於任何 DB／prefs 存取（上面全是 lazy，此時都還沒建）
        com.nekoyaku.ss.data.backup.BackupManager.applyPendingRestore(this)
        val pkg = runCatching { packageManager.getPackageInfo(packageName, 0) }.getOrNull()
        DebugLog.i(
            "app",
            "啟動 ver=${pkg?.versionName}(${pkg?.longVersionCode}) Android ${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT}) device=${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}
