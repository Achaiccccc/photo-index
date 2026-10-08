package app.photoindex

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize().systemBarSafePadding()) {
                    var page by rememberSaveable { mutableStateOf("scope") }
                    var settingsReturn by rememberSaveable { mutableStateOf("scope") }
                    when (page) {
                        "settings" -> SettingsScreen(onBack = { page = settingsReturn })
                        "quote" -> QuoteScreen(
                            onBack = { page = "scope" },
                            onOpenSettings = {
                                settingsReturn = "quote"
                                page = "settings"
                            },
                        )
                        else -> ScopeScreen(
                            onOpenSettings = {
                                settingsReturn = "scope"
                                page = "settings"
                            },
                            onOpenQuote = { page = "quote" },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Android 15 起，targetSdk 35 以上会强制把内容铺到系统栏下面。
 * 这里把正文限制在状态栏和底部导航键之间。更早的系统本来就避开这两块，不再额外留白。
 */
@Composable
private fun Modifier.systemBarSafePadding(): Modifier {
    val bars = WindowInsets.systemBars
    if (Build.VERSION.SDK_INT < 35) return this
    return windowInsetsPadding(bars)
}
