package com.nekoyaku.ss

import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nekoyaku.ss.site.SiteRegistry
import com.nekoyaku.ss.ui.browser.BrowserScreen
import com.nekoyaku.ss.ui.debug.DebugScreen
import com.nekoyaku.ss.ui.glossary.GlossaryScreen
import com.nekoyaku.ss.ui.home.HomeScreen
import com.nekoyaku.ss.ui.settings.ProviderManageScreen
import com.nekoyaku.ss.ui.settings.SettingsScreen
import com.nekoyaku.ss.ui.theme.NekoYakuTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            NekoYakuTheme {
                NekoYakuNavHost()
            }
        }
    }

    /**
     * e-ink 友善：在站內瀏覽器閱讀章節頁、且設定開了「音量鍵翻頁」時，音量鍵／翻頁鍵捲一個畫面。
     * 其他畫面音量鍵維持系統行為。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val app = applicationContext as NekoYakuApp
        if (app.isReading.value && app.overlaySettings.volumeKeyPaging) {
            val dir = when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> 1
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PAGE_UP -> -1
                else -> 0
            }
            if (dir != 0) {
                if (event.action == KeyEvent.ACTION_DOWN) app.pageScrollRequest.tryEmit(dir)
                return true // ACTION_UP 也要吃掉，否則系統仍會調音量
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun NekoYakuNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpenUrl = { url -> nav.navigate("browser?url=${Uri.encode(url)}") },
                onOpenGlossary = { bookId -> nav.navigate("glossary/${Uri.encode(bookId)}") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable(
            "browser?url={url}",
            arguments = listOf(navArgument("url") { nullable = true; defaultValue = null }),
        ) { entry ->
            val url = entry.arguments?.getString("url")?.let { Uri.decode(it) }
                ?: SiteRegistry.all().first().homeUrl
            BrowserScreen(
                initialUrl = url,
                onOpenGlossary = { bookId -> nav.navigate("glossary/${Uri.encode(bookId)}") },
                onOpenSettings = { nav.navigate("settings") },
                onBack = { nav.popBackStack() },
            )
        }
        composable("settings") {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onOpenDebug = { nav.navigate("debug") },
                onOpenProviders = { nav.navigate("providers") },
                onOpenAbout = { nav.navigate("about") },
            )
        }
        composable("about") {
            com.nekoyaku.ss.ui.settings.AboutScreen(onBack = { nav.popBackStack() })
        }
        composable("providers") {
            ProviderManageScreen(onBack = { nav.popBackStack() })
        }
        composable("debug") {
            DebugScreen(onBack = { nav.popBackStack() })
        }
        composable("glossary/{bookId}") { entry ->
            val bookId = Uri.decode(entry.arguments?.getString("bookId").orEmpty())
            GlossaryScreen(bookId = bookId, onBack = { nav.popBackStack() })
        }
    }
}
