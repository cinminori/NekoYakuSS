package com.nekoyaku.ss.ui.browser

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.nekoyaku.ss.BuildConfig
import com.nekoyaku.ss.NekoYakuApp
import com.nekoyaku.ss.overlay.OverlayController
import com.nekoyaku.ss.overlay.TrEdit
import com.nekoyaku.ss.site.SiteRegistry
import com.nekoyaku.ss.site.SupportLevel
import com.nekoyaku.ss.ui.components.Hairline
import com.nekoyaku.ss.util.DebugLog
import kotlinx.coroutines.launch

/**
 * 站內瀏覽器：使用者照常在原網站登入、閱讀；打開章節頁時自動把譯文疊進網頁。
 * 上方工具列：顯示模式（雙語／只看譯文／只看原文）、自動翻譯開關、選單（名詞表、重新翻譯…）。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    initialUrl: String,
    onOpenGlossary: (bookId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as NekoYakuApp
    val scope = rememberCoroutineScope()
    val ovs = app.overlaySettings
    val controller = remember { OverlayController(app, scope) }
    val status by controller.status.collectAsState()
    val mode by ovs.mode.collectAsState()
    var auto by remember { mutableStateOf(ovs.autoTranslate) }
    var uiOn by remember { mutableStateOf(ovs.uiTranslate) }
    // rememberSaveable：從瀏覽器進名詞表／設定再回來時 WebView 會重建，要回到剛才那一頁而不是起始頁
    var currentUrl by rememberSaveable { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmTranslateAll by remember { mutableStateOf<Int?>(null) }
    var confirmRetranslate by remember { mutableStateOf<Int?>(null) }
    var showBookNote by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val site = SiteRegistry.forUrl(currentUrl)
    val reading = status.active

    // 音量鍵翻頁：只在章節頁收
    LaunchedEffect(reading) { app.isReading.value = reading }
    DisposableEffect(Unit) {
        onDispose {
            app.isReading.value = false
            controller.detach()
        }
    }
    LaunchedEffect(Unit) {
        app.pageScrollRequest.collect { dir -> controller.page(dir) }
    }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                status.title.takeIf { reading && it.isNotBlank() } ?: pageTitle.ifBlank { site?.displayName ?: "瀏覽器" },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                statusLine(status, site?.support, reading),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "回書架")
                        }
                    },
                    actions = {
                        if (reading) {
                            TextButton(onClick = {
                                val next = mode.next()
                                ovs.setMode(next)
                                controller.setMode(next)
                            }) { Text(mode.label) }
                            TextButton(onClick = {
                                auto = !auto
                                ovs.autoTranslate = auto
                                controller.setAuto(auto)
                                toast(if (auto) "捲動自動翻譯：開" else "捲動自動翻譯：關（可用選單「翻譯整頁」）")
                            }) { Text(if (auto) "自動✓" else "自動✗") }
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "選單")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            if (reading) {
                                DropdownMenuItem(text = { Text("翻譯整頁") }, onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        val n = controller.untranslatedCount()
                                        if (n > CONFIRM_TRANSLATE_ALL_PARAS) confirmTranslateAll = n else controller.translateAll()
                                    }
                                })
                                DropdownMenuItem(text = { Text("名詞表") }, onClick = {
                                    menuOpen = false
                                    scope.launch { controller.currentBookId()?.let(onOpenGlossary) ?: toast("還沒辨識出這一話屬於哪本書") }
                                })
                                DropdownMenuItem(text = { Text("從本話抽取名詞") }, enabled = !busy, onClick = {
                                    menuOpen = false
                                    busy = true
                                    scope.launch {
                                        try { toast(controller.extractGlossaryNow()) } finally { busy = false }
                                    }
                                })
                                DropdownMenuItem(text = { Text("本書翻譯提示詞") }, onClick = {
                                    menuOpen = false
                                    showBookNote = true
                                })
                                DropdownMenuItem(text = { Text("重新翻譯本話") }, onClick = {
                                    menuOpen = false
                                    scope.launch {
                                        val n = controller.overrideCount()
                                        if (n > 0) confirmRetranslate = n else toast(controller.retranslateChapter())
                                    }
                                })
                            }
                            if (site != null) {
                                DropdownMenuItem(
                                    text = { Text(if (uiOn) "網站介面翻譯：開（點此關閉）" else "網站介面翻譯：關（點此開啟）") },
                                    onClick = {
                                        menuOpen = false
                                        uiOn = !uiOn
                                        ovs.uiTranslate = uiOn
                                        controller.setUi(uiOn)
                                    },
                                )
                            }
                            DropdownMenuItem(text = { Text("重新整理") }, onClick = {
                                menuOpen = false
                                webView?.reload()
                            })
                            DropdownMenuItem(text = { Text("前往網站首頁") }, onClick = {
                                menuOpen = false
                                site?.let { webView?.loadUrl(it.homeUrl) }
                            })
                            DropdownMenuItem(text = { Text("設定") }, onClick = {
                                menuOpen = false
                                onOpenSettings()
                            })
                        }
                    },
                )
                status.message?.let { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TextButton(onClick = { controller.clearMessage() }) { Text("知道了") }
                    }
                }
                Hairline()
            }
        },
    ) { inner ->
        AndroidView(
            modifier = Modifier.padding(inner).fillMaxSize(),
            factory = { c ->
                WebView(c).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    WebView.setWebContentsDebuggingEnabled(BuildConfig.WEBVIEW_CDP_DEBUG)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mediaPlaybackRequiresUserGesture = true
                    // 不允許網頁讀本機檔案
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    CookieManager.getInstance().setAcceptCookie(true)
                    // kakao 帳號登入走 accounts.kakao.com，要第三方 cookie
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    controller.attach(this)
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            if (!url.isNullOrBlank()) currentUrl = url
                            controller.onUrlChanged(url, doInject = false)
                        }

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            // SPA 站內換頁（kakaopage／ridi 換話）不會觸發 onPageStarted
                            if (!url.isNullOrBlank()) currentUrl = url
                            controller.onUrlChanged(url)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            CookieManager.getInstance().flush()
                            pageTitle = view?.title.orEmpty()
                            controller.onUrlChanged(url)
                        }

                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? =
                            request?.url?.let { controller.interceptFont(it) } ?: super.shouldInterceptRequest(view, request)

                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return false
                            val scheme = uri.scheme?.lowercase()
                            if (scheme == "http" || scheme == "https") return false
                            // intent://、kakaotalk:// 之類：交給系統（裝了對應 App 才開得起來）
                            return openExternal(c, uri, view)
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) {
                                DebugLog.w("browse", "頁面載入失敗 code=${error?.errorCode} ${error?.description} url=${request.url}")
                            }
                        }
                    }
                    webView = this
                    loadUrl(currentUrl)
                }
            },
            onRelease = { it.destroy() },
        )
    }

    if (showBookNote) {
        BookNoteDialog(controller = controller, onDismiss = { showBookNote = false })
    }
    confirmRetranslate?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmRetranslate = null },
            title = { Text("重新翻譯本話？") },
            text = { Text("這一話有 $n 處你單獨改過的譯文，重新翻譯會一併清掉。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRetranslate = null
                    scope.launch { toast(controller.retranslateChapter()) }
                }) { Text("清掉並重翻") }
            },
            dismissButton = { TextButton(onClick = { confirmRetranslate = null }) { Text("取消") } },
        )
    }
    val trEdit by controller.trEdit.collectAsState()
    trEdit?.let { e ->
        key(e) { TrEditDialog(controller = controller, edit = e, onDismiss = controller::dismissTrEdit) }
    }
    confirmTranslateAll?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmTranslateAll = null },
            title = { Text("翻譯整話？") },
            text = { Text("這一話還有 $n 段沒翻，全部翻完會送出很多次請求，API 費用也會比較高。\n\n只想往下讀的話，打開「自動」就好，捲到哪翻到哪。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmTranslateAll = null
                    controller.translateAll()
                }) { Text("全部翻譯") }
            },
            dismissButton = { TextButton(onClick = { confirmTranslateAll = null }) { Text("取消") } },
        )
    }
}

/** 「翻譯整頁」超過這個段數先問一聲（一般連載一話約一兩百段，電子書型可達數千段）。 */
private const val CONFIRM_TRANSLATE_ALL_PARAS = 500

private fun statusLine(s: com.nekoyaku.ss.overlay.OverlayStatus, support: SupportLevel?, reading: Boolean): String = when {
    support == null -> "非支援網站：只能瀏覽"
    !reading -> "打開任一話即自動疊翻（${support.label}）"
    s.extractingGlossary -> "抽取專有名詞中…"
    s.translating > 0 -> "翻譯中 ${s.translating} 段｜已翻 ${s.translated}｜快取 ${s.fromCache}"
    s.translated + s.fromCache > 0 -> "已翻 ${s.translated} 段｜快取 ${s.fromCache} 段"
    else -> "疊翻待命（${support.label}）"
}

/** 非 http(s) 連結：intent:// 照 Android 規則解析，能開就開，開不了就退回 fallback 網址。 */
private fun openExternal(c: android.content.Context, uri: Uri, view: WebView?): Boolean {
    return try {
        val intent = if (uri.scheme.equals("intent", ignoreCase = true)) {
            Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                // 安全：不讓網頁指定元件或拿到 URI 權限
                component = null
                selector = null
                flags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION).inv()
                addCategory(Intent.CATEGORY_BROWSABLE)
            }
        } else {
            Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
        }
        try {
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            intent.getStringExtra("browser_fallback_url")
                ?.takeIf { it.startsWith("https://") }
                ?.let { view?.loadUrl(it) }
        }
        true
    } catch (e: Exception) {
        DebugLog.w("browse", "無法開啟外部連結：${uri.scheme}")
        true
    }
}

/** 長按譯文：只改這一處的譯文，手動修改或重翻。 */
@Composable
private fun TrEditDialog(controller: OverlayController, edit: TrEdit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(edit.translation) }
    var translating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        // 有未儲存的修改，點外面不關閉
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("修改這一處譯文") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("原文", style = MaterialTheme.typography.labelMedium)
                Text(edit.text, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text("譯文") },
                    enabled = !translating,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text(
                    "只改這一處，其他相同的句子不受影響。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(4.dp))
                TextButton(enabled = !translating, onClick = {
                    translating = true
                    error = null
                    scope.launch {
                        runCatching { controller.retranslateOne(edit) }
                            .onSuccess { text = it }
                            .onFailure { error = "重翻失敗：${it.message?.take(80)}" }
                        translating = false
                    }
                }) { Text(if (translating) "重翻中⋯" else "重翻這段（送一次請求）") }
                if (edit.overridden) {
                    TextButton(enabled = !translating, onClick = {
                        scope.launch {
                            controller.clearOverride(edit)
                            onDismiss()
                        }
                    }) { Text("恢復共用譯文") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !translating && text.isNotBlank(), onClick = {
                scope.launch {
                    controller.saveOverride(edit, text.trim())
                    onDismiss()
                }
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 本書翻譯提示詞（題材背景、用語領域），每次翻譯都會帶給模型。 */
@Composable
private fun BookNoteDialog(controller: OverlayController, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as NekoYakuApp
    val scope = rememberCoroutineScope()
    var bookId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val id = controller.currentBookId()
        bookId = id
        if (id != null) {
            val b = app.cacheRepository.getBook(id.substringBefore('|'), id.substringAfter('|'))
            note = b?.promptNote.orEmpty()
            alias = b?.aliasZh.orEmpty()
        }
        loaded = true
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本書設定") },
        text = {
            Column {
                if (loaded && bookId == null) {
                    Text("還沒辨識出這一話屬於哪本書，請等正文載入後再試。")
                } else {
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        label = { Text("中文書名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(1500) },
                        label = { Text("翻譯提示詞（例：棒球題材，커브 譯「曲球」）") },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = bookId != null,
                onClick = {
                    val id = bookId ?: return@TextButton
                    scope.launch {
                        app.cacheRepository.updateBook(id.substringBefore('|'), id.substringAfter('|')) {
                            it.copy(promptNote = note.trim(), aliasZh = alias.trim())
                        }
                        onDismiss()
                    }
                },
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
