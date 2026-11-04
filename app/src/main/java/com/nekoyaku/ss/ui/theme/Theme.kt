package com.nekoyaku.ss.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * 設計系統：Editorial Minimalism（e-ink 版）— 2026-07 重設計（校樣 №1）
 * - 純單色高對比，無漸層、無陰影、無動畫（e-ink 友善）
 * - 強調＝反白（Ink 底 Paper 字）是唯一強調手段，一個畫面只有一顆反白主鈕
 * - 全域方角（印刷書語彙：細線框、雙線、書脊格），圓角一律 0
 * - 襯線＝書的聲音（書名/章題/正文），無襯線＝UI 的聲音（標籤/按鈕/頁眉）
 */

// ── 紙感單色 token ──
private val Ink = Color(0xFF141414)        // 主文字（近黑）
private val InkSoft = Color(0xFF5A564E)    // 次要文字（暖灰）
private val Paper = Color(0xFFFBFAF7)      // 紙白底
private val PaperCard = Color(0xFFEFEDE7)  // 卡片/區塊底
private val Line = Color(0xFFC9C5BC)       // 分隔線/邊框

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperCard,
    onSurfaceVariant = InkSoft,
    outline = Line,
    outlineVariant = Line,
    error = Color(0xFF8A1F1F),
)

// 深色＝e-ink 反白（純黑底 + 近白字）
private val DarkColors = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF0B0B0B),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFFB8B5AE),
    outline = Color(0xFF555049),
    outlineVariant = Color(0xFF555049),
    error = Color(0xFFE08585),
)

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

/*
 * 字階（校樣 №1 §02）——slot 對應：
 *   displaySmall  書名 26/34 Serif Bold（繼續閱讀卡、書名頁題頭）
 *   titleLarge    章題 20/28 Serif Bold（章節標題、TopAppBar）
 *   titleMedium   列表主標 15/22 Sans Medium（書架列、章節列）
 *   bodyLarge     譯文 19/34 Serif（行距放大，正文呼吸）
 *   bodyMedium    原文 14/24 Serif（引文式對照）
 *   labelLarge    按鈕/UI 標籤 13/18 Sans Medium
 *   labelMedium   meta 11/16 Sans（時間、話數）
 *   labelSmall    頁眉/分組標 10/14 Sans Medium，寬字距
 */
private val ReadingTypography = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Serif, fontSize = 19.sp, lineHeight = 34.sp),   // 正文譯文
    bodyMedium = TextStyle(fontFamily = Serif, fontSize = 14.sp, lineHeight = 24.sp),  // 原文
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.02.em),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 11.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.15.em),
)

// 全域方角：對話框、輸入框、卡片、下拉選單一律直角（印刷感；e-ink 圓角抗鋸齒也吃虧）
private val SquareShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun NekoYakuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ReadingTypography,
        shapes = SquareShapes,
        content = content,
    )
}
