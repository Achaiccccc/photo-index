package app.photoindex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
                Surface(modifier = Modifier.fillMaxSize()) {
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
