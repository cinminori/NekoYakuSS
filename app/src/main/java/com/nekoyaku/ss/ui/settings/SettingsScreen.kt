package com.nekoyaku.ss.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nekoyaku.ss.BuildConfig
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.overlay.ScreenMode
import kotlinx.coroutines.launch
import com.nekoyaku.ss.ui.components.GroupLabel
import com.nekoyaku.ss.ui.components.Hairline
import com.nekoyaku.ss.ui.components.HairlineButton
import com.nekoyaku.ss.ui.components.SectionLabel
import com.nekoyaku.ss.ui.components.ToggleHairlineButton

/**
 * 設定：AI 翻譯服務、疊翻顯示、e-ink、儲存空間、除錯紀錄。
 * 本 App 不含任何收費、帳號或分析追蹤；API key 只存在這台裝置上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDebug: () -> Unit = {},
    onOpenProviders: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as NekoYakuApp
    val s = app.overlaySettings
    val store = app.aiProfileStore
    val profiles by store.profiles.collectAsState()
    val selectedId by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    var autoGlossary by remember { mutableStateOf(s.autoGlossary) }
    var autoTitle by remember { mutableStateOf(s.autoTitle) }
    var translateImages by remember { mutableStateOf(s.translateImages) }
    var trFont by remember { mutableStateOf(s.trFont) }
    var fontName by remember { mutableStateOf(s.customFontName) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // 匯入字型檔：複製到 App 私有目錄，瀏覽器從網站同源的假路徑讀（見 OverlayController.interceptFont）
    val fontPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        s.customFontFile.outputStream().use { input.copyTo(it) }
                    }
                }
            }.isSuccess
            if (ok) {
                val name = runCatching {
                    ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                }.getOrNull() ?: "自訂字型"
                s.customFontName = name
                fontName = name
                s.trFont = com.nekoyaku.ss.overlay.TrFont.CUSTOM
                trFont = com.nekoyaku.ss.overlay.TrFont.CUSTOM
            }
        }
    }
    var uiTranslate by remember { mutableStateOf(s.uiTranslate) }
    var fixPaged by remember { mutableStateOf(s.fixPagedMode) }
    var uiEngine by remember { mutableStateOf(s.uiEngine) }
    var autoTranslate by remember { mutableStateOf(s.autoTranslate) }
    var scale by remember { mutableStateOf(s.scale) }
    var ahead by remember { mutableStateOf(s.aheadScreens) }
    var screenMode by remember { mutableStateOf(s.screenMode) }
    var volumePaging by remember { mutableStateOf(s.volumeKeyPaging) }
    var stats by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(tick) { stats = runCatching { app.cacheRepository.cacheStats() }.getOrNull() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column {
                    SectionLabel("AI 翻譯服務", Modifier.padding(top = 10.dp, bottom = 14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            .clickable(onClick = onOpenProviders)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (profiles.isEmpty()) "管理翻譯服務（尚無，點此新增）" else "管理翻譯服務（${profiles.size} 個）",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "自備 API key：支援 OpenAI 相容端點（DeepSeek、OpenRouter、Gemini、Groq、OpenAI…）與 Claude 官方 API。" +
                            "key 只存在這台裝置，翻譯時直接連到你選的服務，不經過任何第三方伺服器。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            if (profiles.isNotEmpty()) {
                item {
                    Column {
                        GroupLabel("使用中的翻譯服務")
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            profiles.forEach { p ->
                                SlotChip(p.name, selectedId == p.id || (selectedId == null && p.id == profiles.firstOrNull()?.id)) {
                                    store.select(p.id)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Column {
                    SectionLabel("疊翻", Modifier.padding(top = 28.dp, bottom = 6.dp))
                    SwitchRow("捲動自動翻譯", "捲到畫面附近的段落才送翻；關掉時用選單「翻譯整頁」。", autoTranslate) {
                        autoTranslate = it; s.autoTranslate = it
                    }
                    SwitchRow("自動抽專有名詞", "每話第一次翻譯前先抽人名、地名等加進該書名詞表，譯名才會前後一致（多一次 API 請求）。", autoGlossary) {
                        autoGlossary = it; s.autoGlossary = it
                    }
                    SwitchRow("翻譯插圖裡的文字", "kakaopage 作者常把特效文字、聊天畫面做成圖片。需要支援圖片輸入的模型（如 Gemini、GPT-4o、Claude），每張圖一次請求。", translateImages) {
                        translateImages = it; s.translateImages = it
                    }
                    SwitchRow("網站介面翻譯", "把網站的選單、按鈕、作品頁等短字串直接換成中文（關掉會還原）。瀏覽器選單也可以隨時切換。", uiTranslate) {
                        uiTranslate = it; s.uiTranslate = it
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        com.nekoyaku.ss.overlay.UiEngine.entries.forEach { e ->
                            ToggleHairlineButton(
                                text = e.label,
                                checked = uiEngine == e,
                                onClick = { uiEngine = e; s.uiEngine = e },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Text(
                        if (uiEngine == com.nekoyaku.ss.overlay.UiEngine.GOOGLE_FREE) {
                            "Google 免費：不花錢，但走非官方端點，可能隨時失效或被限流；介面文字會送到 Google。"
                        } else {
                            "AI 翻譯服務：用使用中的 AI 服務翻介面文字（會花一點 API 費用）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SwitchRow("翻頁模式自動改成捲動", "kakaopage 的翻頁模式不會把譯文算進總頁數，翻到一半會跳到最後一頁；偵測到時自動改用捲動模式（電子紙可用音量鍵一次捲一個畫面）。", fixPaged) {
                        fixPaged = it; s.fixPagedMode = it
                    }
                    SwitchRow("自動翻譯書名", "書架上的書沒有中文書名時翻一次（不覆蓋手動填的）。", autoTitle) {
                        autoTitle = it; s.autoTitle = it
                    }
                    Spacer(Modifier.height(8.dp))
                    GroupLabel("預先翻譯範圍：畫面下方 $ahead 個畫面")
                    Slider(
                        value = ahead.toFloat(),
                        onValueChange = { ahead = it.toInt().coerceIn(1, 4) },
                        onValueChangeFinished = { s.aheadScreens = ahead },
                        valueRange = 1f..4f,
                        steps = 2,
                        colors = inkSliderColors(),
                    )
                    GroupLabel("譯文字級：${(scale * 100).toInt()}%")
                    Slider(
                        value = scale,
                        onValueChange = { scale = (Math.round(it * 20) / 20f).coerceIn(0.7f, 1.6f) },
                        onValueChangeFinished = { s.scale = scale },
                        valueRange = 0.7f..1.6f,
                        colors = inkSliderColors(),
                    )
                }
            }

            item {
                Column {
                    SectionLabel("譯文字型", Modifier.padding(top = 28.dp, bottom = 6.dp))
                    Text(
                        "網頁的韓文字型配中文常常很怪。黑體／明體用手機內建的繁體中文字型；也可以匯入自己的字型檔（.ttf／.otf）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.nekoyaku.ss.overlay.TrFont.entries.filter { it != com.nekoyaku.ss.overlay.TrFont.CUSTOM || s.customFontFile.exists() }.forEach { f ->
                            ToggleHairlineButton(
                                text = f.label,
                                checked = trFont == f,
                                onClick = { trFont = f; s.trFont = f },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HairlineButton(if (fontName.isBlank()) "匯入字型檔…" else "換字型檔…", { fontPicker.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream")) })
                        if (fontName.isNotBlank()) {
                            Text(fontName, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                            HairlineButton("移除", {
                                s.customFontFile.delete()
                                s.customFontName = ""
                                fontName = ""
                                if (trFont == com.nekoyaku.ss.overlay.TrFont.CUSTOM) { trFont = com.nekoyaku.ss.overlay.TrFont.SERIF; s.trFont = trFont }
                            })
                        }
                    }
                    Text(
                        "字型在下次打開或重新整理章節時套用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SectionLabel("電子紙", Modifier.padding(top = 28.dp, bottom = 6.dp))
                    GroupLabel("螢幕類型")
                    Text(
                        "譯文一律用左側直線與原文區隔（不靠顏色）；電子紙模式加粗直線與字重，待翻用虛線、失敗用雙線。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScreenMode.entries.forEach { m ->
                            ToggleHairlineButton(
                                text = if (m == ScreenMode.AUTO) (if (s.isEinkDevice) "自動（電子紙）" else "自動（一般）") else m.label,
                                checked = screenMode == m,
                                onClick = { screenMode = m; s.screenMode = m },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SwitchRow("音量鍵翻頁", "閱讀章節時，音量鍵上／下捲動一個畫面（無平滑捲動，減少殘影）。", volumePaging) {
                        volumePaging = it; s.volumeKeyPaging = it
                    }
                    Text(
                        "顯示設定在下次打開章節時套用。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Column {
                    SectionLabel("儲存空間", Modifier.padding(top = 28.dp, bottom = 10.dp))
                    val st = stats
                    Text(
                        if (st == null) "統計中…" else "段落譯文快取 ${st.first} 段・約 ${st.second / 1024} KB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    HairlineButton("清除全部譯文快取", { confirmClear = true })
                    Text(
                        "只存「段落雜湊 → 譯文」，不存原文。清除後再讀會重新翻譯（重花 API 費用）；書架與名詞表不受影響。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            item { BackupSection() }

            item {
                Column {
                    SectionLabel("其他", Modifier.padding(top = 28.dp, bottom = 4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenDebug)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("除錯紀錄", style = MaterialTheme.typography.titleMedium)
                        Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenAbout)
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("關於（授權、隱私、開源元件）", style = MaterialTheme.typography.titleMedium)
                        Text("›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "NekoYakuSS " + BuildConfig.VERSION_NAME + "\n" +
                            "不含收費、帳號系統或分析追蹤。閱讀內容的著作權屬於原網站與作者，請以自己的帳號合法閱讀。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }
        }

        if (confirmClear) {
            AlertDialog(
                onDismissRequest = { confirmClear = false },
                title = { Text("清除全部譯文快取？") },
                text = { Text("之後重讀需要重新翻譯（會重花 API 費用）。書架與名詞表保留。") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmClear = false
                        scope.launch {
                            app.cacheRepository.clearAllTranslations()
                            tick++
                        }
                    }) { Text("清除") }
                },
                dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // 說明文字與開關之間留白，不要貼在一起
        Spacer(Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = MaterialTheme.colorScheme.onSurface,
                uncheckedThumbColor = MaterialTheme.colorScheme.onSurface,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}

/** 黑白滑桿：預設的淡紫色軌道在電子紙上幾乎看不見。 */
@Composable
private fun inkSliderColors() = androidx.compose.material3.SliderDefaults.colors(
    thumbColor = MaterialTheme.colorScheme.onSurface,
    activeTrackColor = MaterialTheme.colorScheme.onSurface,
    inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
    activeTickColor = MaterialTheme.colorScheme.surface,
    inactiveTickColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** 選擇 chip（反白＝選中；e-ink 上靠黑白反轉辨識，不靠顏色）。 */
@Composable
internal fun SlotChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
