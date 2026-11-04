package com.nekoyaku.ss.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.data.backup.BackupManager
import com.nekoyaku.ss.ui.components.HairlineButton
import com.nekoyaku.ss.ui.components.SectionLabel
import com.nekoyaku.ss.ui.components.ToggleHairlineButton
import com.nekoyaku.ss.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 備份／還原的畫面狀態（常駐文字，不用會消失的 toast：e-ink 上沒看到就沒了）。 */
private sealed interface BackupUi {
    data object Idle : BackupUi
    data class Running(val phase: String, val done: Long, val total: Long) : BackupUi
    data class Done(val message: String) : BackupUi
    data class Failed(val message: String) : BackupUi

    /** 挑好檔、讀完 manifest，等使用者按「還原並重新啟動」。 */
    data class ConfirmRestore(val uri: Uri, val manifest: BackupManager.Manifest) : BackupUi

    /** 已解到暫存目錄，只差重新啟動。 */
    data object RestoreReady : BackupUi
}

/**
 * 工作跑在 process 級 scope、狀態也放這裡：使用者等幾十秒很可能按返回，
 * 綁在畫面的 scope 會一離開就取消，在使用者選的位置留下半截 zip。
 */
private object BackupController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow<BackupUi>(BackupUi.Idle)
    val state = _state.asStateFlow()

    private val busy: Boolean get() = _state.value is BackupUi.Running

    fun reset() {
        if (!busy) _state.value = BackupUi.Idle
    }

    fun backup(ctx: Context, app: NekoYakuApp, uri: Uri, includeKeys: Boolean) {
        if (busy) return
        _state.value = BackupUi.Running("準備中…", 0, 0)
        scope.launch {
            runCatching {
                BackupManager.backup(ctx.applicationContext, app.database, uri, includeKeys) { phase, done, total ->
                    _state.value = BackupUi.Running(phase, done, total)
                }
            }.onSuccess { bytes ->
                _state.value = BackupUi.Done("備份完成・內容 ${fmtSize(bytes)}。請把檔案另外存到手機以外的地方。")
            }.onFailure { t ->
                DebugLog.e("backup", "備份失敗", t)
                _state.value = BackupUi.Failed("備份失敗：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun peek(ctx: Context, uri: Uri) {
        if (busy) return
        _state.value = BackupUi.Running("正在讀取備份檔…", 0, 0)
        scope.launch {
            runCatching { BackupManager.peek(ctx.applicationContext, uri) }
                .onSuccess { m ->
                    val err = BackupManager.compatibilityError(m)
                    _state.value = if (err != null) BackupUi.Failed(err) else BackupUi.ConfirmRestore(uri, m)
                }
                .onFailure { t -> _state.value = BackupUi.Failed("無法還原：${t.message ?: t.javaClass.simpleName}") }
        }
    }

    fun restore(ctx: Context, uri: Uri, manifest: BackupManager.Manifest) {
        if (busy) return
        _state.value = BackupUi.Running("準備中…", 0, manifest.totalBytes)
        scope.launch {
            runCatching {
                BackupManager.stageRestore(ctx.applicationContext, uri, manifest) { phase, done, total ->
                    _state.value = BackupUi.Running(phase, done, total)
                }
            }.onSuccess {
                _state.value = BackupUi.RestoreReady
            }.onFailure { t ->
                DebugLog.e("backup", "還原失敗", t)
                _state.value = BackupUi.Failed("還原失敗：${t.message ?: t.javaClass.simpleName}")
            }
        }
    }
}

/** 設定頁「備份與還原」：整包 zip 存到使用者選的位置／從 zip 還原並重新啟動。 */
@Composable
internal fun BackupSection() {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as NekoYakuApp
    val state by BackupController.state.collectAsState()
    val busy = state is BackupUi.Running
    var includeKeys by rememberSaveable { mutableStateOf(false) }
    var estimate by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { estimate = runCatching { BackupManager.estimateBytes(ctx) }.getOrNull() }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) BackupController.backup(ctx, app, uri, includeKeys) }
    // 檔案總管常把 zip 報成 octet-stream，指定 MIME 會讓檔案灰掉挑不到
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) BackupController.peek(ctx, uri) }

    Column {
        SectionLabel("備份與還原", Modifier.padding(top = 28.dp, bottom = 10.dp))
        Text(
            "把譯文快取、單處修改、書架、名詞表和設定打包成一個 zip 檔。手機壞掉、誤刪 App 或換機時，挑這個檔還原就能接回。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            estimate?.let { "目前資料約 ${fmtSize(it)}" } ?: "統計中…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        ToggleHairlineButton(
            text = "含 API key",
            checked = includeKeys,
            onClick = { includeKeys = !includeKeys },
        )
        Text(
            "勾選後 API key 會以明文放進 zip，備份檔請自己保管好、不要傳給別人。不勾選的話，還原後要重新輸入 key。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HairlineButton(
                "備份到檔案…",
                {
                    BackupController.reset()
                    backupLauncher.launch("NekoYakuSS-備份-${fileStamp()}.zip")
                },
                enabled = !busy,
            )
            HairlineButton(
                "從檔案還原…",
                {
                    BackupController.reset()
                    restoreLauncher.launch(arrayOf("*/*"))
                },
                enabled = !busy,
                quiet = true,
            )
        }
        when (val s = state) {
            is BackupUi.Running -> {
                Spacer(Modifier.height(12.dp))
                InkProgressBar(if (s.total > 0) s.done.toFloat() / s.total else null)
                Text(
                    if (s.total > 0) "${s.phase} ${fmtSize(s.done)}／${fmtSize(s.total)}" else s.phase,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            is BackupUi.Done -> Text(s.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp))
            is BackupUi.Failed -> Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 10.dp),
            )
            else -> Unit
        }
        Text(
            "還原會取代這台目前的資料，然後重新啟動 App。網站登入狀態不在備份內，還原後請重新登入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp),
        )
    }

    (state as? BackupUi.ConfirmRestore)?.let { c ->
        val m = c.manifest
        AlertDialog(
            onDismissRequest = { BackupController.reset() },
            title = { Text("從備份還原？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("備份時間：${fmtTime(m.createdAt)}")
                    Text("來源：NekoYakuSS ${m.appVersion}・${m.device}")
                    Text("內容：譯文 ${m.translations} 段・書架 ${m.books} 本・名詞 ${m.glossary} 個・" + if (m.includesAiProfiles) "含 API key" else "不含 API key")
                    Text(
                        "這台目前的譯文、書架、名詞表與設定會被取代，無法復原。完成後 App 會重新啟動。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { BackupController.restore(ctx, c.uri, m) }) { Text("還原並重新啟動") } },
            dismissButton = { TextButton(onClick = { BackupController.reset() }) { Text("取消") } },
        )
    }

    // 解好了只剩重啟。不給關：關掉繼續用舊資料，之後寫進去的東西下次啟動會被蓋掉
    if (state is BackupUi.RestoreReady) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("還原資料已就緒") },
            text = { Text("需要重新啟動 NekoYakuSS 才會套用。若重新啟動後沒有自動打開，請手動開啟一次。") },
            confirmButton = { TextButton(onClick = { BackupManager.relaunch(ctx) }) { Text("立即重新啟動") } },
        )
    }
}

/** 1dp 框＋實心填色：M3 的進度條有動畫，e-ink 會一直刷。fraction null＝總量未知，只畫空框。 */
@Composable
private fun InkProgressBar(fraction: Float?) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .border(1.dp, MaterialTheme.colorScheme.primary)
            .padding(2.dp),
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 10L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    else -> "${bytes / (1024 * 1024)} MB"
}

private fun fmtTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

private fun fileStamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())
