package com.gazw89.goodhive.debug

import android.app.Application
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.gazw89.goodhive.ui.reports.ReportPeriodKind
import com.gazw89.goodhive.ui.reports.ReportsContent
import com.gazw89.goodhive.ui.reports.RoomReportContent
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.GoodHiveTheme
import java.util.Locale

/**
 * Shell-only renderer of the real stateless screens. Deliberately no Hilt, auth, preferences,
 * database, worker scheduling or production navigation. Chart/table interactions remain real.
 * Extras: screen=overview|room, theme=light|dark|dynamic|dynamic-dark,
 * status=ready|no-data|partial|loading|error, period=week|month|year, offset=0..4,
 * room=demo-living|demo-study|demo-spare, fontScale (float)=0.85..2.0.
 */
class ReportsFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Fail closed if the debug manifest/factory isolation was accidentally removed.
        check(application.javaClass == Application::class.java) { "Fixture Application isolation missing" }
        enableEdgeToEdge()
        val theme = intent.getStringExtra("theme")?.lowercase(Locale.ROOT) ?: "light"
        val dark = theme == "dark" || theme == "dynamic-dark"
        val dynamic = theme.startsWith("dynamic")
        val status = when (intent.getStringExtra("status")?.lowercase(Locale.ROOT)) {
            "no-data", "no_data", "empty" -> ReportsFixtures.Status.NO_DATA
            "partial" -> ReportsFixtures.Status.PARTIAL
            "loading" -> ReportsFixtures.Status.LOADING
            "error" -> ReportsFixtures.Status.ERROR
            else -> ReportsFixtures.Status.READY
        }
        val fontScale = intent.getFloatExtra("fontScale", 1f).let {
            if (it.isFinite()) it.coerceIn(0.85f, 2f) else 1f
        }
        setContent {
            // Local override stress-tests Compose layout without changing device/user settings.
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                GoodHiveTheme(darkTheme = dark, dynamicColor = dynamic) {
                    SideEffect {
                        WindowCompat.getInsetsController(window, window.decorView).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    var screen by rememberSaveable {
                        mutableStateOf(if (intent.getStringExtra("screen") in listOf("room", "drilldown")) "room" else "overview")
                    }
                    var roomId by rememberSaveable {
                        mutableStateOf(intent.getStringExtra("room")?.takeIf {
                            it in listOf("demo-living", "demo-study", "demo-spare")
                        } ?: ReportsFixtures.DEFAULT_ROOM)
                    }
                    var period by rememberSaveable {
                        mutableStateOf(ReportPeriodKind.entries.firstOrNull {
                            it.name.equals(intent.getStringExtra("period"), ignoreCase = true)
                        } ?: ReportPeriodKind.WEEK)
                    }
                    var offset by rememberSaveable { mutableIntStateOf(intent.getIntExtra("offset", 0).coerceIn(0, 4)) }
                    val fixture = remember(status, period, offset, roomId) {
                        ReportsFixtures.build(status, period, offset, roomId)
                    }
                    val demoAction = {
                        Toast.makeText(this, "Demo/test data · action stays offline", Toast.LENGTH_SHORT).show()
                    }
                    val selectPeriod: (ReportPeriodKind) -> Unit = { period = it; offset = 0 }
                    val previous: () -> Unit = { offset = (offset + 1).coerceAtMost(4) }
                    val next: () -> Unit = { offset = (offset - 1).coerceAtLeast(0) }
                    BackHandler(enabled = screen == "room") { screen = "overview" }
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer) {
                                Column(Modifier.padding(horizontal = GoodHiveSpacing.lg, vertical = GoodHiveSpacing.sm)) {
                                    Text("Demo/test data · Offline fixtures", style = MaterialTheme.typography.labelLarge)
                                    Text("$screen · ${period.noun} · ${status.name.lowercase(Locale.ROOT)} · $theme · ${fontScale}×",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            key(screen, roomId, period, offset, status) {
                                if (screen == "room") {
                                    RoomReportContent(
                                        state = fixture.room, onSelectKind = selectPeriod,
                                        onPrevious = previous, onNext = next,
                                        onBack = { screen = "overview" },
                                        onOpenRoomControls = demoAction, onOpenSchedule = demoAction,
                                        onOpenSettings = demoAction, onRetry = demoAction,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    ReportsContent(
                                        state = fixture.overview, onSelectKind = selectPeriod,
                                        onPrevious = previous, onNext = next,
                                        onOpenRoom = { roomId = it; screen = "room" },
                                        onOpenSettings = demoAction, onRetry = demoAction,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
