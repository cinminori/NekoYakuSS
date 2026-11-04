package com.nekoyaku.ss.ui.glossary

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.data.db.GlossaryEntity
import com.nekoyaku.ss.ui.components.GroupLabel
import com.nekoyaku.ss.ui.components.Hairline
import com.nekoyaku.ss.ui.components.InkButton
import kotlinx.coroutines.launch

/** 名詞表管理：AI 候選確認、手動新增/編輯/刪除。每本書一份（bookId = "siteId|bookKey"）。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GlossaryScreen(bookId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NekoYakuApp
    val repo = app.glossaryRepository
    val scope = rememberCoroutineScope()
    // 名詞表共用：同作品連載版/ebook 版共用一份（bookId = "siteId|bookKey"）
    val siteId = remember(bookId) { bookId.substringBefore('|') }
    val bookKey = remember(bookId) { bookId.substringAfter('|') }
    val shareTarget by remember(bookId) { app.cacheRepository.bookShareFlow(siteId, bookKey) }.collectAsState(initial = null)
    val records by remember { app.cacheRepository.recordsFlow() }.collectAsState(initial = emptyList())
    val aliases by remember { app.cacheRepository.bookAliasesFlow() }.collectAsState(initial = emptyMap())
    var showSharePicker by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    // Room Flow：glossary 表有寫入（含翻譯時 pre-extract 自動採用）自動推新，免手動刷新
    val items by remember(bookId) { repo.forBookFlow(bookId) }.collectAsState(initial = emptyList())
    var term by remember { mutableStateOf("") }
    var zh by remember { mutableStateOf("") }
    var newType by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }
    // 搜尋：詞一多就難找——原詞／譯名／備註都比對，邊打邊過濾候選與正式表
    var query by remember { mutableStateOf("") }

    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    fun toast(msg: String) = scope.launch { snackbarHostState.showSnackbar(msg) }
    // 匯出：SAF 選存檔位置寫 JSON（可存到雲端硬碟／傳給別台）
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val json = repo.exportJson(bookId)
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            }.isSuccess
            toast(if (ok) "名詞表已匯出" else "匯出失敗（無法寫入所選位置）")
        }
    }
    // 匯入：SAF 選 JSON 檔 → 問「併入／取代」（0.5.78；取代＝匯出整理後匯回的批量刪改）
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                }
            }.onSuccess { pendingImport = it }
                .onFailure { toast("匯入失敗（無法讀取檔案）") }
        }
    }
    fun runImport(text: String, replace: Boolean) {
        scope.launch {
            runCatching { repo.importJson(bookId, text, replace) }
                .onSuccess { toast(if (replace) "已取代為 $it 筆名詞" else "已匯入 $it 筆名詞") }
                .onFailure { toast("匯入失敗（檔案格式不符或沒有名詞）") }
        }
    }
    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("匯入名詞表") },
            text = {
                Text(
                    "併入：檔案裡的詞加進本書（同名詞以檔案為準），其他保留。\n" +
                        "取代：先刪除本書所有已確認名詞，只留下檔案裡的（AI 候選不動）。適合匯出整理後再匯回。",
                )
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { pendingImport = null; runImport(text, replace = true) }) {
                        Text("取代", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { pendingImport = null; runImport(text, replace = false) }) { Text("併入") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }

    // 共用目標挑選：列書架上其他書（顯示譯名優先）
    if (showSharePicker) {
        val otherBooks = records.map { it.siteId to it.bookKey }.distinct()
            .filter { (s, k) -> !(s == siteId && k == bookKey) }
            .map { (s, k) ->
                val id = "$s|$k"
                val title = aliases[id]
                    ?: records.first { r -> r.siteId == s && r.bookKey == k }.bookTitle.takeIf { it.isNotBlank() }
                    ?: k
                id to title
            }
        AlertDialog(
            onDismissRequest = { showSharePicker = false },
            title = { Text("與哪本書共用名詞表？") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "本書名詞會併入所選書（同名詞以對方為準），之後兩本書共用同一份名詞表。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (otherBooks.isEmpty()) {
                        Text("書架上沒有其他書", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    otherBooks.forEach { (id, title) ->
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showSharePicker = false
                                    scope.launch {
                                        // 對方自己也在共用→直接指到它的源頭；源頭若是本書＝對方已共用過來，毋需再設
                                        val target = repo.resolveTarget(id)
                                        if (target == bookId) {
                                            toast("《$title》已與本書共用，無需設定")
                                        } else {
                                            val moved = repo.mergeInto(bookId, target)
                                            app.cacheRepository.saveGlossaryShare(siteId, bookKey, target)
                                            toast("已與《$title》共用名詞表（併入 $moved 筆）")
                                        }
                                    }
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showSharePicker = false }) { Text("取消") } },
        )
    }

    // 譯文取代：舊譯名→新譯名，直接改寫本書快取譯文（人名不統一救援；無法復原→先查詢再取代）
    if (showReplace) {
        var fromText by remember { mutableStateOf("") }
        var toText by remember { mutableStateOf("") }
        var hitCount by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { showReplace = false },
            title = { Text("譯文取代") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "把本書已翻譯文中的「舊譯名」逐字取代為「新譯名」。直接改寫快取、無法復原——先查詢確認範圍再取代。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = fromText,
                        onValueChange = { fromText = it; hitCount = null }, // 改字就作廢舊查詢，防按錯範圍
                        label = { Text("舊譯名（要被換掉的字）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = toText,
                        onValueChange = { toText = it },
                        label = { Text("新譯名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 名詞表既有譯名 chips：點一下填入「新譯名」（共用後本篇的正確譯名就在表裡）
                    val zhChips = items.filter { it.confirmed && it.translation.isNotBlank() }
                        .map { it.translation }.distinct().take(30)
                    if (zhChips.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "點名詞表譯名填入新譯名：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                            zhChips.forEach { z ->
                                Text(
                                    z,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                        .clickable { toText = z }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                    hitCount?.let { n ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (n == 0) "沒有譯文包含「${fromText.trim()}」" else "共 $n 段譯文包含「${fromText.trim()}」",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            },
            confirmButton = {
                val n = hitCount
                if (n == null) {
                    TextButton(
                        enabled = fromText.isNotBlank(),
                        onClick = {
                            scope.launch {
                                hitCount = app.cacheRepository.countTranslationsContaining(siteId, bookKey, fromText.trim())
                            }
                        },
                    ) { Text("查詢") }
                } else {
                    TextButton(
                        enabled = n > 0 && toText.isNotBlank() && toText.trim() != fromText.trim(),
                        onClick = {
                            showReplace = false
                            scope.launch {
                                val changed = app.cacheRepository.replaceInTranslations(siteId, bookKey, fromText.trim(), toText.trim())
                                toast("已取代 $changed 段譯文（重開章節生效）")
                            }
                        },
                    ) { Text("取代 $n 段") }
                }
            },
            dismissButton = { TextButton(onClick = { showReplace = false }) { Text("取消") } },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("名詞表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("匯入") }
                    TextButton(onClick = { exportLauncher.launch("nekoyakuss-glossary.json") }) { Text("匯出") }
                },
            )
        },
    ) { padding ->
        val q = query.trim()
        val searching = q.isNotEmpty()
        fun matches(e: GlossaryEntity) =
            e.term.contains(q, ignoreCase = true) || e.translation.contains(q) || e.note.contains(q)
        val candidates = items.filter { !it.confirmed }.filter { !searching || matches(it) }
        val confirmed = items.filter { it.confirmed }.filter { !searching || matches(it) }

        LazyColumn(modifier = Modifier.padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp)) {
            // 搜尋欄 stickyHeader 釘頂（捲多深都在）：找既有名詞來修正／確認用
            stickyHeader {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("搜尋名詞（原詞或譯名）") },
                    singleLine = true,
                    trailingIcon = if (searching) {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, contentDescription = "清除搜尋") } }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(top = 6.dp),
                )
            }
            // 名詞表共用：同作品連載版／ebook 版共用一份（外傳沒先套本篇名詞表的救援＋永久機制）
            item {
                Spacer(Modifier.height(10.dp))
                // 搜尋中收起共用／譯文取代（跟找詞無關），保留下方編輯區讓點列可改
                if (!searching) {
                    GroupLabel("名詞表共用")
                    val targetTitle = shareTarget?.let { t ->
                        aliases[t]
                            ?: records.firstOrNull { r -> "${r.siteId}|${r.bookKey}" == t }?.bookTitle?.takeIf { it.isNotBlank() }
                            ?: t.substringAfter('|')
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (targetTitle == null) "獨立名詞表" else "與《$targetTitle》共用中",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (shareTarget == null) {
                            TextButton(onClick = { showSharePicker = true }) { Text("設定共用") }
                        } else {
                            TextButton(onClick = {
                                scope.launch {
                                    app.cacheRepository.saveGlossaryShare(siteId, bookKey, "")
                                    toast("已取消共用（名詞留在對方書）")
                                }
                            }) { Text("取消共用") }
                        }
                    }
                    Text(
                        "同作品的連載版／ebook 版共用一份名詞表：設定後本書名詞併入對方（同名詞以對方為準），之後兩邊讀寫、AI 抽詞都是同一份。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                    GroupLabel("譯文取代")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "修正已翻譯文裡的名詞（直接改快取，不重翻不花 API）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { showReplace = true }) { Text("開始") }
                    }
                    Spacer(Modifier.height(4.dp))
                    Hairline()
                    Spacer(Modifier.height(4.dp))
                }
                GroupLabel("手動新增／編輯")
                OutlinedTextField(
                    value = term, onValueChange = { term = it },
                    label = { Text("韓文原詞") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                // 分類選擇（反白＝選中；再點一次取消）。人名/地名/組織/設定＝翻譯照表固定，稱呼＝依上下文靈活
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    Text("分類", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("人名", "稱呼", "地名", "組織", "設定").forEach { t ->
                        val selected = newType == t
                        Text(
                            t,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surface)
                                .clickable { newType = if (selected) "" else t }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                // 備註／譯注：翻譯時整句餵給模型當背景（梗詞、圈內用語、翻譯理由都放這）
                OutlinedTextField(
                    value = newNote, onValueChange = { newNote = it },
                    label = { Text("備註／譯注（翻譯時會餵給模型）") }, maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = zh, onValueChange = { zh = it },
                        label = { Text("繁中譯名") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    InkButton(
                        "儲存",
                        {
                            if (term.isNotBlank() && zh.isNotBlank()) {
                                val t = term.trim(); val z = zh.trim()
                                // 編輯既有詞：分類/備註用填的、沒填才沿用舊值（舊版這裡會把 type/note 洗掉）
                                val old = items.firstOrNull { it.term == t }
                                val ty = newType.ifBlank { old?.type.orEmpty() }
                                val nt = newNote.trim().ifBlank { old?.note.orEmpty() }
                                scope.launch {
                                    repo.upsert(GlossaryEntity(bookId, t, z, nt, true, ty, System.currentTimeMillis()))
                                    term = ""; zh = ""; newType = ""; newNote = ""
                                }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Hairline()
            }

            if (candidates.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                        GroupLabel("AI 候選（${candidates.size}）")
                        Text(
                            "✓ 加入正式、垃圾桶移除、點列可編輯",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(candidates.size) { i ->
                    val e = candidates[i]
                    GlossaryRow(
                        entry = e,
                        onEdit = { term = e.term; zh = e.translation; newType = e.type; newNote = e.note },
                        onConfirm = {
                            scope.launch { repo.confirm(bookId, e.term, System.currentTimeMillis()) }
                        },
                        onDelete = {
                            scope.launch { repo.delete(bookId, e.term) }
                        },
                    )
                }
            }

            item {
                GroupLabel(
                    "正式名詞表（${confirmed.size}）",
                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp),
                )
                if (confirmed.isEmpty() && candidates.isEmpty()) {
                    Text(
                        if (searching) {
                            "沒有名詞符合「$q」"
                        } else {
                            "尚無名詞。翻譯一章後 AI 會自動抽出候選，或在上方手動新增。"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // 名詞表分級：依分類分組（人名→稱呼→地名→組織→設定→未分類），組內維持原排序
            val typeOrder = listOf("人名", "稱呼", "地名", "組織", "設定")
            val groups = confirmed.groupBy { it.type.ifBlank { "未分類" } }.toList()
                .sortedBy { (t, _) -> typeOrder.indexOf(t).takeIf { it >= 0 } ?: typeOrder.size }
            groups.forEach { (type, list) ->
                if (groups.size > 1) {
                    item("type-$type") {
                        Text(
                            "$type（${list.size}）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                    }
                }
                items(list.size, key = { "g-$type-${list[it].term}" }) { i ->
                    val e = list[i]
                    GlossaryRow(
                        entry = e,
                        onEdit = { term = e.term; zh = e.translation; newType = e.type; newNote = e.note },
                        onConfirm = null,
                        onDelete = {
                            scope.launch { repo.delete(bookId, e.term) }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun GlossaryRow(
    entry: GlossaryEntity,
    onEdit: () -> Unit,
    onConfirm: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onConfirm != null) {
            IconButton(onClick = onConfirm) { Icon(Icons.Filled.Check, contentDescription = "加入正式") }
        }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text("${entry.term} → ${entry.translation}", style = MaterialTheme.typography.bodyLarge)
            val meta = listOf(entry.type, entry.note).filter { it.isNotBlank() }.joinToString(" ・ ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "刪除") }
    }
    HorizontalDivider()
}
