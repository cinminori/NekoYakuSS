package com.nekoyaku.ss.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.ai.AiProfile
import com.nekoyaku.ss.ai.ProviderPresets
import com.nekoyaku.ss.ui.components.GroupLabel
import com.nekoyaku.ss.ui.components.SectionLabel
import com.nekoyaku.ss.ui.components.HairlineButton
import com.nekoyaku.ss.ui.components.InkButton
import com.nekoyaku.ss.ui.components.ToggleHairlineButton
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * AI 翻譯服務管理：卡片清單（點卡＝設為使用中）
 * ＋新增/編輯表單（範本快捷、抓模型驗證）。模型「指派」在設定主頁的 chips，這裡只管 CRUD。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderManageScreen(onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NekoYakuApp
    val store = app.aiProfileStore
    val translator = app.translationService
    val profiles by store.profiles.collectAsState()
    val selectedId by store.selectedId.collectAsState()
    val scope = rememberCoroutineScope()

    var editingId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var disableThinking by remember { mutableStateOf(false) }

    fun clearForm() {
        editingId = null; name = ""; baseUrl = ""; apiKey = ""; model = ""; models = emptyList()
        disableThinking = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Provider 管理") },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SectionLabel("PROVIDER 清單", Modifier.padding(top = 10.dp, bottom = 6.dp)) }
            if (profiles.isEmpty()) {
                item {
                    Text(
                        "尚無 provider——下方選個範本快速新增。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                            p.model + " ・ " + p.baseUrl,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = {
                        editingId = p.id; name = p.name; baseUrl = p.baseUrl
                        apiKey = p.apiKey; model = p.model; models = emptyList()
                        disableThinking = p.disableThinking; status = "編輯中：${p.name}"
                    }) {
                        Icon(Icons.Filled.Edit, contentDescription = "編輯", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { store.remove(p.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "刪除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SectionLabel(
                    if (editingId == null) "新增 PROVIDER" else "編輯 PROVIDER",
                    Modifier.padding(top = 30.dp, bottom = 6.dp),
                )
            }

            // 範本快捷
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProviderPresets.ALL.forEach { preset ->
                        AssistChip(
                            onClick = {
                                if (name.isBlank()) name = preset.name
                                baseUrl = preset.baseUrl
                                if (model.isBlank()) model = preset.sampleModel
                                status = if (preset.baseUrl.contains('<')) {
                                    "已套用範本：${preset.name}，先把 Base URL 的 <…> 改成實際位址，再填 API Key 按「抓模型」"
                                } else {
                                    "已套用範本：${preset.name}，填 API Key 後按「抓模型」"
                                }
                            },
                            label = { Text(preset.name) },
                        )
                    }
                }
            }

            item {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名稱") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text("Base URL（含 /v1）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            }
            // 模型欄 + 抓模型 + 下拉
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = model, onValueChange = { model = it },
                        label = { Text("模型") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                        trailingIcon = {
                            if (models.isNotEmpty()) {
                                IconButton(onClick = { modelMenu = true }) {
                                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "選模型")
                                }
                            }
                        },
                    )
                    DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                        models.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { model = m; modelMenu = false })
                        }
                    }
                    HairlineButton(
                        "抓模型",
                        {
                            scope.launch {
                                status = "抓模型中…"
                                translator.fetchModels(baseUrl.trim(), apiKey.trim()).fold(
                                    onSuccess = {
                                        models = it
                                        modelMenu = it.isNotEmpty()
                                        status = "✅ 連線成功，抓到 ${it.size} 個模型，點模型欄右側箭頭選"
                                    },
                                    onFailure = { status = "❌ ${it.message}" },
                                )
                            }
                        },
                        modifier = Modifier.padding(start = 8.dp).wrapContentWidth(),
                    )
                }
            }

            // 關閉思考模式：DeepSeek V4（pro／flash 都預設開思考）與 Groq qwen3 系翻譯時推理
            // 吃掉大量時間與 max_tokens——翻譯有名詞表與規則約束，不需要 chain-of-thought。
            // 依端點分流：Groq 送 reasoning_effort=none、其他送 thinking=disabled（0.5.54）
            item {
                Column {
                    ToggleHairlineButton(
                        "關閉思考模式（DeepSeek／Groq Qwen）",
                        disableThinking,
                        { disableThinking = !disableThinking },
                        Modifier.fillMaxWidth(),
                    )
                    Text(
                        "DeepSeek V4（pro／flash）與 Groq 的 Qwen 系都預設開啟思考，整章翻譯會慢上數倍、" +
                            "還常把 token 用光回空內容（Groq 免費層更會加速吃光每分鐘配額）。" +
                            "勾選後依端點自動帶對參數：Groq 送 reasoning_effort=none、其他送 thinking=disabled。" +
                            "其餘 provider 請保持不勾。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InkButton(
                        if (editingId == null) "新增" else "更新",
                        {
                            if (name.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()) {
                                store.upsert(
                                    AiProfile(
                                        id = editingId ?: UUID.randomUUID().toString(),
                                        name = name.trim(), baseUrl = baseUrl.trim(),
                                        apiKey = apiKey.trim(), model = model.trim(),
                                        disableThinking = disableThinking,
                                    ),
                                )
                                status = if (editingId == null) "已新增" else "已更新"
                                clearForm()
                            } else {
                                status = "名稱 / Base URL / 模型 不可空白"
                            }
                        },
                    )
                    if (editingId != null) {
                        HairlineButton("取消", { clearForm(); status = "已取消編輯" })
                    }
                }
            }
            status?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}
