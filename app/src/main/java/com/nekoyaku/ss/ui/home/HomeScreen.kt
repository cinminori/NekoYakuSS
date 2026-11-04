package com.nekoyaku.ss.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.data.db.BookEntity
import com.nekoyaku.ss.data.db.GlossaryRepository
import com.nekoyaku.ss.site.SiteRegistry
import com.nekoyaku.ss.site.SupportLevel
import com.nekoyaku.ss.ui.components.Hairline
import com.nekoyaku.ss.ui.components.HairlineButton
import com.nekoyaku.ss.ui.components.SectionLabel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 首頁：上半部是四個站的入口（附支援狀況），下半部是書架。
 * 書＝作品＋名詞表＋閱讀紀錄；點書直接在站內瀏覽器開到上次讀的那一話。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    onOpenUrl: (String) -> Unit,
    onOpenGlossary: (bookId: String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as NekoYakuApp
    val books by remember { app.cacheRepository.recordsFlow() }.collectAsState(initial = emptyList())
    val profiles by app.aiProfileStore.profiles.collectAsState()
    var editing by remember { mutableStateOf<BookEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NekoYakuSS") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "設定")
                    }
                },
            )
        },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.padding(inner).fillMaxSize().padding(horizontal = 16.dp),
        ) {
            if (profiles.isEmpty()) {
                item {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        Text("尚未設定 AI 翻譯服務", fontWeight = FontWeight.Bold)
                        Text(
                            "請先到設定新增一組（自備 API key，例如 DeepSeek、Gemini、OpenAI、Claude 官方 API）。" +
                                "沒有設定也能瀏覽網站，只是不會翻譯。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        HairlineButton("前往設定", onOpenSettings)
                    }
                    Hairline()
                }
            }
            item { SectionLabel("網站", Modifier.padding(vertical = 8.dp)) }
            items(SiteRegistry.all(), key = { "site-" + it.id }) { site ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = { onOpenUrl(site.homeUrl) })
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(site.displayName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            site.supportNote,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "［${site.support.label}］",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (site.support == SupportLevel.SUPPORTED) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                Hairline()
            }
            item { SectionLabel("書架", Modifier.padding(top = 20.dp, bottom = 8.dp)) }
            if (books.isEmpty()) {
                item {
                    Text(
                        "還沒有書。從上面的網站打開任一話，就會自動加進書架。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(books, key = { "book-" + it.siteId + "|" + it.bookKey }) { book ->
                BookRow(
                    book = book,
                    onOpen = { if (book.lastUrl.isNotBlank()) onOpenUrl(book.lastUrl) },
                    onLongPress = { editing = book },
                )
                Hairline()
            }
            item {
                Text(
                    "長按書本可編輯書名、提示詞、開名詞表或從書架移除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }

    editing?.let { b ->
        BookEditDialog(
            book = b,
            onDismiss = { editing = null },
            onOpenGlossary = {
                editing = null
                onOpenGlossary(GlossaryRepository.bookId(b.siteId, b.bookKey))
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(book: BookEntity, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val site = SiteRegistry.byId(book.siteId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .padding(vertical = 10.dp),
    ) {
        Text(
            book.aliasZh.ifBlank { book.bookTitle.ifBlank { book.bookKey } },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.aliasZh.isNotBlank() && book.bookTitle.isNotBlank()) {
            Text(book.bookTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // 網站＋時間一行、上次讀到另起一行：擠在同一列時，長話名會把時間擠成一字一行的直條
        Spacer(Modifier.height(4.dp))
        Text(
            listOfNotNull(
                site?.displayName ?: book.siteId,
                book.updatedAt.takeIf { it > 0 }?.let { DATE_FMT.format(Date(it)) },
            ).joinToString("・"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "上次讀到：${book.lastChapterTitle.ifBlank { book.lastChapterKey }}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val DATE_FMT = SimpleDateFormat("M/d HH:mm", Locale.TAIWAN)

@Composable
private fun BookEditDialog(book: BookEntity, onDismiss: () -> Unit, onOpenGlossary: () -> Unit) {
    val app = LocalContext.current.applicationContext as NekoYakuApp
    val scope = rememberCoroutineScope()
    var alias by remember { mutableStateOf(book.aliasZh) }
    var title by remember { mutableStateOf(book.bookTitle) }
    var note by remember { mutableStateOf(book.promptNote) }
    var confirmDelete by remember { mutableStateOf(false) }
    var alsoCache by remember { mutableStateOf(false) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("從書架移除？") },
            text = {
                Column {
                    Text("名詞表會保留，之後再打開這本書的任一話會自動回到書架。")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = alsoCache, onCheckedChange = { alsoCache = it })
                        Text("一併刪除這本書的譯文快取")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        app.cacheRepository.deleteBook(book.siteId, book.bookKey, alsoCache)
                        confirmDelete = false
                        onDismiss()
                    }
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("編輯書本") },
        text = {
            Column {
                OutlinedTextField(alias, { alias = it }, label = { Text("中文書名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(title, { title = it }, label = { Text("原文書名") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                OutlinedTextField(
                    note,
                    { note = it.take(1500) },
                    label = { Text("翻譯提示詞") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HairlineButton("名詞表", onOpenGlossary)
                    HairlineButton("從書架移除", { confirmDelete = true })
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    app.cacheRepository.updateBook(book.siteId, book.bookKey) {
                        it.copy(aliasZh = alias.trim(), bookTitle = title.trim(), promptNote = note.trim())
                    }
                    onDismiss()
                }
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
