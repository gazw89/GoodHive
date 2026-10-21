package com.gazw89.goodhive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.prefs.ThemeMode
import com.gazw89.goodhive.data.prefs.ThemePreferences
import com.gazw89.goodhive.data.repository.HeatingRepository
import com.gazw89.goodhive.ui.navigation.GoodHiveNavHost
import com.gazw89.goodhive.ui.theme.GoodHiveTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themePreferences: ThemePreferences
    @Inject lateinit var heatingRepository: HeatingRepository

    override fun onStart() {
        super.onStart()
        heatingRepository.setForegroundActive(true)
    }

    override fun onStop() {
        heatingRepository.setForegroundActive(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themePreferences.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            val dynamicColor by themePreferences.dynamicColor.collectAsStateWithLifecycle(
                initialValue = false,
            )
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
            GoodHiveTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                // A themed backstop behind the NavHost's animated content: during a screen
                // transition, the sliding/fading composables briefly don't fully cover the
                // window, and without this the platform's raw (white) window background could
                // show through instead of the app's own background color.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    GoodHiveNavHost()
                }
            }
        }
    }
}
