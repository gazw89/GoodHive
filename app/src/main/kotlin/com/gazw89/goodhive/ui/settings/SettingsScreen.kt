package com.gazw89.goodhive.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.data.prefs.ThemeMode
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by viewModel.dynamicColor.collectAsStateWithLifecycle()
    val coldNightThresholdC by viewModel.coldNightThresholdC.collectAsStateWithLifecycle()
    val storedHistory by viewModel.storedHistory.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GoodHiveSpacing.lg)
                .padding(bottom = GoodHiveSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xl),
        ) {
            AppearanceSection(
                themeMode = themeMode,
                dynamicColor = dynamicColor,
                onThemeModeChange = viewModel::setThemeMode,
                onDynamicColorChange = viewModel::setDynamicColor,
            )
            ReportsSection(
                coldNightThresholdC = coldNightThresholdC,
                storedHistory = storedHistory,
                onColdNightThresholdChange = viewModel::setColdNightThreshold,
                onSyncNow = viewModel::syncNow,
                onClearHistory = viewModel::clearStoredHistory,
            )
            AccountSection(
                email = viewModel.accountEmail,
                onSignOut = {
                    viewModel.signOut()
                    onSignedOut()
                },
            )
            AboutNavRow(onClick = onOpenAbout)
        }
    }
}

@Composable
private fun AppearanceSection(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        Text(
            text = "Appearance",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Choose light, dark, or match the system setting.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(GoodHiveSpacing.xs))
        val modes = ThemeMode.entries
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = themeMode == mode,
                    onClick = { onThemeModeChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(mode.label)
                }
            }
        }
        ListItem(
            headlineContent = { Text("Dynamic color") },
            supportingContent = {
                Text("Use Material You colors from your wallpaper")
            },
            trailingContent = {
                Switch(
                    checked = dynamicColor,
                    onCheckedChange = onDynamicColorChange,
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun AccountSection(
    email: String?,
    onSignOut: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        Text(
            text = "Account",
            style = MaterialTheme.typography.titleMedium,
        )
        if (!email.isNullOrBlank()) {
            Text(
                text = email,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        Text(
            text = "Signs out of Hive on this device. Saved email and password stay so sign-in is quicker next time.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(GoodHiveSpacing.xs))
        OutlinedButton(
            onClick = onSignOut,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(GoodHiveSpacing.sm))
            Text("Log out")
        }
    }
}

@Composable
private fun AboutNavRow(onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        Text(
            text = "App",
            style = MaterialTheme.typography.titleMedium,
        )
        ListItem(
            headlineContent = { Text("About") },
            supportingContent = {
                Text("Version and Hive session metrics")
            },
            leadingContent = {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.NavigateNext,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
        )
    }
}

private val ThemeMode.label: String
    get() = when (this) {
        ThemeMode.SYSTEM -> "System"
        ThemeMode.LIGHT -> "Light"
        ThemeMode.DARK -> "Dark"
    }
