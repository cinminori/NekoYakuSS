package com.nekoyaku.ss.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.ai.AiProfile
import com.nekoyaku.ss.ai.ProviderPresets
import com.nekoyaku.ss.ui.components.HairlineButton
import com.nekoyaku.ss.ui.components.InkButton
import com.nekoyaku.ss.ui.components.SectionLabel
import com.nekoyaku.ss.ui.components.ToggleHairlineButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

private const val CUSTOM_SERVICE = "其他（自訂網址）"
private const val MIN_KEY_LENGTH = 16
private val NON_CHAT_MODEL = Regex(
    "embed|whisper|tts|moderation|guard|dall-e|imag|veo|aqa|audio|computer-use|deep-research|antigravity|robotics|live|transcribe|nano-banana|customtools|lyria",
    RegexOption.IGNORE_CASE,
)

/**
 * 翻譯服務管理：卡片清單（點卡＝設為使用中）＋三步驟表單（選服務 → 貼 key → 選模型）。
 * 網址、名稱、思考模式收進進階設定，選了範本就不必碰。
 * 表單用 rememberSaveable：申請 key 要跳去瀏覽器，App 被系統回收後回來不能清空。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManageScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as NekoYakuApp
    val store = app.aiProfileStore
    val translator = app.translationService
    val profiles by store.profiles.collectAsState()
    val selectedId by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var service by rememberSaveable { mutableStateOf<String?>(null) } // 範本名稱或 CUSTOM_SERVICE；null＝還沒選
    var name by rememberSaveable { mutableStateOf("") }
    var baseUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var disableThinking by rememberSaveable { mutableStateOf(false) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var modelsStatus by remember { mutableStateOf<FormStatus?>(null) }
    var testing by remember { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<FormStatus?>(null) }
    var saveStatus by remember { mutableStateOf<FormStatus?>(null) }

    val preset = ProviderPresets.ALL.firstOrNull { it.name == service }
    val isCustom = service == CUSTOM_SERVICE

    fun clearForm() {
        editingId = null; service = null; name = ""; baseUrl = ""; apiKey = ""; model = ""
        disableThinking = false; showKey = false; showAdvanced = false
        models = emptyList(); modelsStatus = null; testStatus = null
    }

    fun chooseService(next: String) {
        if (next == service) return
        val nextPreset = ProviderPresets.ALL.firstOrNull { it.name == next }
        if (name.isBlank() || name == preset?.name) name = nextPreset?.name.orEmpty()
        service = next
        baseUrl = nextPreset?.baseUrl.orEmpty()
        apiKey = ""
        model = nextPreset?.sampleModel.orEmpty()
        disableThinking = nextPreset?.disableThinking ?: false
        models = emptyList(); modelsStatus = null; saveStatus = null
    }

    fun formProfile() = AiProfile(
        id = editingId ?: UUID.randomUUID().toString(),
        name = name.trim().ifBlank { Uri.parse(baseUrl.trim()).host ?: baseUrl.trim() },
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        model = model.trim(),
        disableThinking = disableThinking,
    )

    // 貼上 key 後自動抓模型清單（抓清單不計費）；等打字停下來才送
    LaunchedEffect(baseUrl, apiKey) {
        models = emptyList(); modelsStatus = null
        if (service == null || baseUrl.isBlank() || apiKey.trim().length < MIN_KEY_LENGTH) return@LaunchedEffect
        delay(700)
        modelsStatus = FormStatus("正在讀取模型清單…")
        translator.fetchModels(baseUrl.trim(), apiKey.trim()).fold(
            onSuccess = { all ->
                models = all.filterNot { NON_CHAT_MODEL.containsMatchIn(it) }.sortedDescending() // 新版號排前面
                // 範本預設模型已下架就清掉，讓使用者從清單選
                if (model == preset?.sampleModel && models.none { it.removePrefix("models/") == model }) model = ""
                modelsStatus = FormStatus("已取得 ${models.size} 個模型。")
            },
            onFailure = { modelsStatus = FormStatus(friendlyError(it), isError = true) },
        )
    }
    LaunchedEffect(baseUrl, apiKey, model, disableThinking) { testStatus = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("翻譯服務") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (profiles.isNotEmpty()) {
                item {
                    Column {
                        SectionLabel("已設定的服務", Modifier.padding(top = 10.dp, bottom = 6.dp))
                        if (profiles.size > 1) HelperText("點一下卡片就會切換成使用中。")
                    }
                }
            }
            items(profiles, key = { it.id }) { p ->
                val active = p.id == selectedId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .border(
                            1.dp,
                            if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        )
                        .clickable { store.select(p.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (active) {
                        Box(
                            Modifier
                                .width(4.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (active) {
                                Spacer(Modifier.width(8.dp))
                                Box(Modifier.background(MaterialTheme.colorScheme.primary).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text(
                                        "使用中",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                        Text(
                            p.model,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        editingId = p.id; name = p.name; baseUrl = p.baseUrl
                        apiKey = p.apiKey; model = p.model; disableThinking = p.disableThinking
                        service = ProviderPresets.matching(p.baseUrl)?.name ?: CUSTOM_SERVICE
                        showKey = false; saveStatus = null
                        scope.launch { listState.scrollToItem(1 + profiles.size) }
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = "編輯", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        store.remove(p.id)
                        if (editingId == p.id) clearForm()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionLabel(
                    if (editingId == null) "新增翻譯服務" else "編輯：$name",
                    Modifier.padding(top = if (profiles.isEmpty()) 10.dp else 30.dp, bottom = 6.dp),
                )
            }

            // 步驟 1：選服務
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepTitle("1. 選擇翻譯服務")
                    (ProviderPresets.ALL.map { it.name } + CUSTOM_SERVICE).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { option ->
                                ToggleHairlineButton(option, service == option, { chooseService(option) }, Modifier.weight(1f).height(56.dp))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    if (service == null) {
                        HelperText("不確定的話，DeepSeek 便宜且預付儲值不會超支；Gemini 有免費額度可以先試。")
                    }
                    preset?.let { HelperText(it.note) }
                    if (isCustom) {
                        OutlinedTextField(
                            value = baseUrl, onValueChange = { baseUrl = it },
                            label = { Text("服務網址（OpenAI 相容，含 /v1）") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (service != null) {
                // 步驟 2：貼 key
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepTitle("2. 貼上 API Key", Modifier.padding(top = 16.dp))
                        preset?.let { p ->
                            HairlineButton(
                                "開啟 ${p.name} 申請頁面",
                                {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(p.keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                        )
                                    }
                                },
                                Modifier.fillMaxWidth(),
                            )
                            HelperText("登入後建立一組 API Key，複製回來貼在下面。")
                        }
                        OutlinedTextField(
                            value = apiKey, onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隱藏" else "顯示") }
                            },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        modelsStatus?.let { StatusText(it) }
                        HelperText("Key 只存在這台裝置，翻譯時直接連到你選的服務。")
                    }
                }

                // 步驟 3：選模型
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StepTitle("3. 確認模型", Modifier.padding(top = 16.dp))
                        OutlinedTextField(
                            value = model, onValueChange = { model = it },
                            label = { Text("模型") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (models.isNotEmpty()) {
                            Box {
                                HairlineButton("從清單選擇（${models.size} 個）", { modelMenu = true }, Modifier.fillMaxWidth())
                                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                    models.forEach { m ->
                                        DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelMenu = false })
                                    }
                                }
                            }
                        }
                        if (preset != null && model.isNotBlank()) HelperText("不確定的話，用預先填好的模型就可以。")
                        HairlineButton(
                            if (testing) "測試中…" else "測試連線",
                            {
                                testing = true
                                testStatus = null
                                scope.launch {
                                    translator.testConnection(formProfile()).fold(
                                        onSuccess = { testStatus = FormStatus("測試成功，模型回覆：${it.take(40)}") },
                                        onFailure = { testStatus = FormStatus(friendlyError(it), isError = true) },
                                    )
                                    testing = false
                                }
                            },
                            Modifier.fillMaxWidth(),
                            enabled = !testing && baseUrl.isNotBlank() && model.isNotBlank(),
                        )
                        testStatus?.let { StatusText(it) }
                        HelperText("測試會真的翻譯一個詞，費用極少。")
                    }
                }

                // 進階設定
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { showAdvanced = !showAdvanced }) {
                            Text(if (showAdvanced) "收起進階設定" else "進階設定（通常不用改）")
                        }
                        if (showAdvanced) {
                            OutlinedTextField(
                                value = name, onValueChange = { name = it },
                                label = { Text("顯示名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            )
                            if (!isCustom) {
                                OutlinedTextField(
                                    value = baseUrl, onValueChange = { baseUrl = it },
                                    label = { Text("服務網址") }, singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            // 關閉思考模式：DeepSeek V4（pro／flash 都預設開思考）與 Groq qwen3 系翻譯時推理
                            // 吃掉大量時間與 max_tokens——翻譯有名詞表與規則約束，不需要 chain-of-thought。
                            // 依端點分流：Groq 送 reasoning_effort=none、其他送 thinking=disabled（0.5.54）
                            ToggleHairlineButton(
                                "關閉思考模式（DeepSeek）",
                                disableThinking,
                                { disableThinking = !disableThinking },
                                Modifier.fillMaxWidth(),
                            )
                            HelperText(
                                "DeepSeek V4 預設會先「思考」，翻譯會慢上數倍，還可能回空內容；" +
                                    "選 DeepSeek 時已自動開啟。其他服務請保持關閉。",
                            )
                        }
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InkButton(
                            "儲存",
                            {
                                val problem = when {
                                    baseUrl.isBlank() -> "請填入服務網址。"
                                    preset != null && apiKey.isBlank() -> "請貼上 API Key。"
                                    model.isBlank() -> "請選擇模型。"
                                    else -> null
                                }
                                if (problem != null) {
                                    saveStatus = FormStatus(problem, isError = true)
                                } else {
                                    store.upsert(formProfile())
                                    saveStatus = FormStatus("已儲存。")
                                    clearForm()
                                    scope.launch { listState.scrollToItem(0) }
                                }
                            },
                        )
                        if (editingId != null) {
                            HairlineButton("取消", { clearForm(); saveStatus = null })
                        }
                    }
                }
            }
            saveStatus?.let { item { StatusText(it) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class FormStatus(val text: String, val isError: Boolean = false)

@Composable
private fun StepTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

@Composable
private fun HelperText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StatusText(status: FormStatus) {
    Text(
        status.text,
        style = MaterialTheme.typography.bodySmall,
        color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

/** 把 HTTP 錯誤翻成白話；看不懂的狀況保留原訊息方便回報。 */
private fun friendlyError(e: Throwable): String {
    val message = e.message.orEmpty()
    val code = Regex("HTTP (\\d{3})").find(message)?.groupValues?.get(1)?.toInt()
    val hint = when {
        code == null -> return if (e is java.io.IOException) "連不上服務，請檢查網路或網址。" else message.take(200)
        code == 401 || code == 403 -> "API Key 不正確或沒有權限，請重新複製一次。"
        code == 402 -> "帳戶餘額不足，請到服務網站儲值。"
        code == 400 -> "服務拒絕了這次請求，請確認 API Key 與模型是否正確。"
        code == 404 -> "找不到這個模型或網址，請重新選擇模型。"
        code == 429 -> "超過用量限制或額度已用完，請稍後再試，或到服務網站查看額度。"
        code >= 500 -> "服務暫時有問題，請稍後再試。"
        else -> "連線失敗。"
    }
    return "$hint（HTTP $code）"
}
