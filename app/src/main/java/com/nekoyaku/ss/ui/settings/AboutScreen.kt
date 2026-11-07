package com.nekoyaku.ss.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nekoyaku.ss.BuildConfig
import com.nekoyaku.ss.ui.components.HairlineButton
import com.nekoyaku.ss.ui.components.SectionLabel

/** 圖文使用說明（Notion，與 NekoYaku 共用）。 */
private const val GUIDE_URL = "https://ancient-freckle-1b1.notion.site/NekoTranslate-3076d20adfde800c9e0bf2c8b0e2e133"

/** 問題回報表單（Google 表單，與 NekoYaku 共用；不需登入）。 */
private const val REPORT_URL = "https://docs.google.com/forms/d/e/1FAIpQLSe8GFtXynqu5n3bNGtQOceyA9c6cICxKqNuEyeiKcadOrD95w/viewform"

/** 本 App 用到的開源元件（GPL-3.0 與 Apache-2.0 相容）。 */
private val OPEN_SOURCE = listOf(
    "AndroidX（Core、Activity、Lifecycle、Navigation、Room）" to "Apache-2.0",
    "Jetpack Compose、Material 3、Material Icons" to "Apache-2.0",
    "Kotlin 標準函式庫、kotlinx.coroutines、kotlinx.serialization" to "Apache-2.0",
    "OkHttp、Okio" to "Apache-2.0",
)

/** 關於：版本、授權、隱私摘要、原始碼、開源元件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    fun open(url: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("關於") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("NekoYakuSS", style = MaterialTheme.typography.headlineSmall)
                    Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "韓文線上小說的網頁疊翻閱讀器：在站內瀏覽器照常閱讀原網站，譯文直接插在原文下方。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (BuildConfig.SOURCE_URL.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        HairlineButton("GitHub 專案頁", { open(BuildConfig.SOURCE_URL) })
                        Text(
                            BuildConfig.SOURCE_URL.removePrefix("https://"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            item {
                Column {
                    SectionLabel("說明與回報", Modifier.padding(top = 16.dp, bottom = 8.dp))
                    HairlineButton("圖文使用說明", { open(GUIDE_URL) })
                    Spacer(Modifier.height(6.dp))
                    HairlineButton("回報問題／建議功能", { open(REPORT_URL) })
                    Text(
                        "回報表單不需要登入；請不要在表單裡填 API key。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                Column {
                    SectionLabel("隱私", Modifier.padding(top = 16.dp, bottom = 8.dp))
                    listOf(
                        "沒有帳號、伺服器、廣告、分析或追蹤程式，開發者收不到你的任何資料。",
                        "API key、書架、名詞表、譯文快取只存在這台裝置；譯文快取只存「段落雜湊 → 譯文」，不存原文。",
                        "要翻譯的正文段落（與名詞表、提示詞、開啟時的插圖）直接送到你自己設定的 AI 翻譯服務。",
                        "開啟「網站介面翻譯」並選「Google 免費」時，網站的選單、按鈕等短字串會送到 Google。",
                    ).forEach {
                        Text("・$it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            item {
                Column {
                    SectionLabel("授權", Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Text(
                        "本程式是自由軟體，依 GNU General Public License 第 3 版（GPL-3.0）授權：" +
                            "你可以自由使用、研究、修改與散布；散布修改後的版本時，也必須以 GPL-3.0 公開原始碼。" +
                            "本程式不提供任何擔保。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    HairlineButton("GPL-3.0 全文", { open("https://www.gnu.org/licenses/gpl-3.0.html") })
                    if (BuildConfig.SOURCE_URL.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        HairlineButton("原始碼", { open(BuildConfig.SOURCE_URL) })
                    }
                }
            }
            item {
                Column {
                    SectionLabel("開源元件", Modifier.padding(top = 16.dp, bottom = 8.dp))
                    OPEN_SOURCE.forEach { (name, license) ->
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(license, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }
            item {
                Column {
                    SectionLabel("著作權", Modifier.padding(top = 16.dp, bottom = 8.dp))
                    Text(
                        "閱讀內容的著作權屬於原網站與作者。本 App 只在你的裝置上、以你自己的帳號與購買權限閱讀時提供翻譯輔助，請勿散布譯文。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 32.dp),
                    )
                }
            }
        }
    }
}
