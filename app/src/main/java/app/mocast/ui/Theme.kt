package app.mocast.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.mocast.data.ThemeMode

/*
 * 白日：暖白纸面 + 白卡片 + 橙色主色。
 * 夜色（参考涛声的夜版约束）：纯黑底（OLED 不发光）+ 中性深灰卡片；文字只用一种白，按透明度分三档；
 * 橙色只做小面积点缀，不用大块橙铺满深色屏幕。越「浮」起来的东西越亮：底 < 卡片 < 面板。
 */
private val Light = lightColorScheme(
    primary = Color(0xFFF07B2E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFDEBDD),
    onPrimaryContainer = Color(0xFF5A2200),
    secondary = Color(0xFF7D7770),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1ECE6),
    onSecondaryContainer = Color(0xFF3A342E),
    background = Color(0xFFF4F2EF),
    onBackground = Color(0xFF1D1B19),
    surface = Color(0xFFF4F2EF),
    onSurface = Color(0xFF1D1B19),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF7D7770),
    outline = Color(0xFFD8D2CA),
    outlineVariant = Color(0xFFE9E4DD),
    inverseSurface = Color(0xFF302C29),
    inverseOnSurface = Color(0xFFF6F0E9)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFF08A4B),
    onPrimary = Color(0xFF1C1C1E),
    primaryContainer = Color(0xFF3A2A20),
    onPrimaryContainer = Color(0xFFFFD9C4),
    secondary = Color(0xFFE7BEB0),
    onSecondary = Color(0xFF3A2318),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xEBFFFFFF),
    background = Color(0xFF000000),
    onBackground = Color(0xEBFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xEBFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0x99FFFFFF),
    outline = Color(0xFF48484A),
    outlineVariant = Color(0x14FFFFFF),
    inverseSurface = Color(0xFFE3E3E6),
    inverseOnSurface = Color(0xFF1C1C1E)
)

@Composable
fun MoCastTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        content = content
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
