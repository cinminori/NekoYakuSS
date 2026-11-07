package com.nekoyaku.ss.overlay

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 疊翻的顯示方式。 */
enum class OverlayMode(val js: String, val label: String) {
    DUAL("dual", "雙語"),
    TRANSLATION("tr", "只看譯文"),
    SOURCE("src", "只看原文"),
    ;

    fun next(): OverlayMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun of(name: String?): OverlayMode = entries.firstOrNull { it.name == name } ?: DUAL
    }
}

/** 網站介面翻譯的引擎。 */
enum class UiEngine(val label: String) { GOOGLE_FREE("Google 免費"), AI("AI 翻譯服務") }

/** 譯文字型。[js] 是注入腳本認得的代號。 */
enum class TrFont(val js: String, val label: String) {
    SITE("inherit", "跟隨網頁"),
    SANS("sans", "黑體"),
    SERIF("serif", "明體"),
    CUSTOM("custom", "自訂字型檔"),
}

/** 螢幕類型：決定譯文標線的粗細與字重。 */
enum class ScreenMode(val label: String) { AUTO("自動判斷"), COLOR("一般螢幕"), EINK("電子紙") }

/** 疊翻與閱讀相關設定（SharedPreferences）。 */
class OverlaySettings(context: Context) {
    private val prefs = context.getSharedPreferences("overlay_settings", Context.MODE_PRIVATE)

    /** 使用者匯入的譯文字型檔（App 私有目錄，不會被其他 App 讀到）。 */
    val customFontFile: java.io.File = java.io.File(context.filesDir, "tr-font")

    /** 譯文字型：預設明體——網頁的韓文字型配繁中字形常常很怪。選了自訂卻沒有字型檔時退回明體。 */
    var trFont: TrFont
        get() = runCatching { TrFont.valueOf(prefs.getString(KEY_FONT, "").orEmpty()) }.getOrDefault(TrFont.SERIF)
            .let { if (it == TrFont.CUSTOM && !customFontFile.exists()) TrFont.SERIF else it }
        set(v) = prefs.edit().putString(KEY_FONT, v.name).apply()

    /** 匯入的字型檔原始檔名（設定頁顯示用）。 */
    var customFontName: String
        get() = prefs.getString(KEY_FONT_NAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_FONT_NAME, v).apply()

    private val _mode = MutableStateFlow(OverlayMode.of(prefs.getString(KEY_MODE, null)))
    val mode: StateFlow<OverlayMode> = _mode.asStateFlow()

    fun setMode(m: OverlayMode) {
        _mode.value = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
    }

    /** 捲到哪翻到哪；關掉就只在按「翻譯本話」時才翻。 */
    var autoTranslate: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    /** 每話第一次翻譯前，先從整話抽專有名詞加進名詞表。 */
    var autoGlossary: Boolean
        get() = prefs.getBoolean(KEY_AUTO_GLOSSARY, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_GLOSSARY, v).apply()

    /** 翻譯正文插圖裡的文字（目前只有 kakaopage；需要支援圖片輸入的模型，每張圖一次請求）。 */
    var translateImages: Boolean
        get() = prefs.getBoolean(KEY_IMAGES, true)
        set(v) = prefs.edit().putBoolean(KEY_IMAGES, v).apply()

    /** 網站介面翻譯（選單、按鈕、作品頁等直接換成中文）。 */
    var uiTranslate: Boolean
        get() = prefs.getBoolean(KEY_UI, false)
        set(v) = prefs.edit().putBoolean(KEY_UI, v).apply()

    var uiEngine: UiEngine
        get() = runCatching { UiEngine.valueOf(prefs.getString(KEY_UI_ENGINE, "").orEmpty()) }.getOrDefault(UiEngine.GOOGLE_FREE)
        set(v) = prefs.edit().putString(KEY_UI_ENGINE, v.name).apply()

    /** 遇到翻頁模式（kakaopage）自動改成捲動模式：翻頁模式的總頁數不會把譯文算進去，翻到一半會跳到最後。 */
    var fixPagedMode: Boolean
        get() = prefs.getBoolean(KEY_FIX_PAGED, true)
        set(v) = prefs.edit().putBoolean(KEY_FIX_PAGED, v).apply()

    /** 站內瀏覽器裡隱藏手機最上方的狀態列（時間、電量）。 */
    var hideStatusBar: Boolean
        get() = prefs.getBoolean(KEY_HIDE_STATUS_BAR, false)
        set(v) = prefs.edit().putBoolean(KEY_HIDE_STATUS_BAR, v).apply()

    /** 書名沒有中文譯名時自動翻一次（只填空白、不覆蓋手動填的）。 */
    var autoTitle: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TITLE, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_TITLE, v).apply()

    /** 譯文字級（相對原文）。 */
    var scale: Float
        get() = prefs.getFloat(KEY_SCALE, 1.0f)
        set(v) = prefs.edit().putFloat(KEY_SCALE, v.coerceIn(0.7f, 1.6f)).apply()

    /** 預先翻譯畫面下方幾個畫面高的段落（越大越不用等、但捲過頭的也會被翻）。 */
    var aheadScreens: Int
        get() = prefs.getInt(KEY_AHEAD, 2)
        set(v) = prefs.edit().putInt(KEY_AHEAD, v.coerceIn(1, 4)).apply()

    var screenMode: ScreenMode
        get() = runCatching { ScreenMode.valueOf(prefs.getString(KEY_SCREEN, "").orEmpty()) }.getOrDefault(ScreenMode.AUTO)
        set(v) = prefs.edit().putString(KEY_SCREEN, v.name).apply()

    val useEink: Boolean
        get() = when (screenMode) {
            ScreenMode.COLOR -> false
            ScreenMode.EINK -> true
            ScreenMode.AUTO -> isEinkDevice
        }

    /** 閱讀網頁時音量鍵翻頁（電子紙機型預設開）。 */
    var volumeKeyPaging: Boolean
        get() = prefs.getBoolean(KEY_VOLUME, isEinkDevice)
        set(v) = prefs.edit().putBoolean(KEY_VOLUME, v).apply()

    /**
     * 這台像不像電子紙。Android 沒有可靠的判斷 API，只能認機型字串——
     * 猜錯的成本只是標線粗細不同，設定頁可以手動指定。
     */
    val isEinkDevice: Boolean by lazy {
        val id = "${Build.MODEL} ${Build.MANUFACTURER} ${Build.BRAND} ${Build.DEVICE}".lowercase()
        EINK_HINTS.any { id.contains(it) }
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_AUTO = "auto_translate"
        const val KEY_AUTO_GLOSSARY = "auto_glossary"
        const val KEY_AUTO_TITLE = "auto_title"
        const val KEY_IMAGES = "translate_images"
        const val KEY_UI = "ui_translate"
        const val KEY_FIX_PAGED = "fix_paged_mode"
        const val KEY_FONT = "tr_font"
        const val KEY_FONT_NAME = "tr_font_name"
        const val KEY_UI_ENGINE = "ui_engine"
        const val KEY_SCALE = "scale"
        const val KEY_AHEAD = "ahead"
        const val KEY_SCREEN = "screen_mode"
        const val KEY_VOLUME = "volume_paging"
        const val KEY_HIDE_STATUS_BAR = "hide_status_bar"
        val EINK_HINTS = listOf(
            "hibreak", "bigme", "boox", "onyx", "eink", "e-ink", "e_ink",
            "supernote", "remarkable", "likebook", "pocketbook", "dasung", "meebook",
        )
    }
}
