package com.gazw89.goodhive.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.auth.SessionDiagnostics
import com.gazw89.goodhive.auth.TokenHistoryEntry
import com.gazw89.goodhive.auth.TokenLifecycleLog
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
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
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.lg),
        ) {
            Text(
                text = "App build and Hive session metrics. Completed ID tokens are kept in a rolling log of the last 10.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MetricRow("App version", "${uiState.versionName} (${uiState.versionCode})")
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CurrentSessionSection(session = uiState.session)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            TokenHistorySection(history = uiState.session.tokenHistory)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RefreshSessionSection(session = uiState.session)
        }
    }
}

@Composable
private fun CurrentSessionSection(session: SessionDiagnostics) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xs)) {
        Text(
            text = "Current ID token",
            style = MaterialTheme.typography.titleMedium,
        )
        MetricRow("Present", yesNo(session.hasIdToken))
        MetricRow(
            "Pulled at",
            when {
                session.idTokenObtainedAtEpochMillis <= 0L -> "—"
                else -> TokenLifecycleLog.format(session.idTokenObtainedAtEpochMillis)
            },
        )
        MetricRow("Pull source", session.lastTokenSource?.replace('_', ' ') ?: "—")
        MetricRow(
            "Age so far",
            when {
                session.idTokenAgeMs < 0L -> "—"
                else -> TokenLifecycleLog.formatDuration(session.idTokenAgeMs)
            },
        )
        MetricRow(
            "Expires at",
            when {
                session.idTokenExpiresAtEpochMillis <= 0L -> "—"
                else -> TokenLifecycleLog.format(session.idTokenExpiresAtEpochMillis)
            },
        )
        MetricRow(
            "Remaining",
            when {
                session.idTokenRemainingMs < 0L -> "—"
                session.idTokenRemainingMs == 0L -> "expired"
                else -> TokenLifecycleLog.formatDuration(session.idTokenRemainingMs)
            },
        )
        MetricRow(
            "Configured TTL",
            if (session.idTokenConfiguredLifetimeSeconds > 0L) {
                TokenLifecycleLog.formatDuration(session.idTokenConfiguredLifetimeSeconds * 1000L)
            } else {
                "—"
            },
        )
    }
}

@Composable
private fun TokenHistorySection(history: List<TokenHistoryEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm)) {
        Text(
            text = "Token history (last ${history.size.coerceAtMost(10)} of 10)",
            style = MaterialTheme.typography.titleMedium,
        )
        if (history.isEmpty()) {
            Text(
                text = "No completed tokens yet. Entries appear after a refresh, soft reauth, or logout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            history.forEachIndexed { index, entry ->
                TokenHistoryCard(index = index + 1, entry = entry)
            }
        }
    }
}

@Composable
private fun TokenHistoryCard(index: Int, entry: TokenHistoryEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(GoodHiveSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xs),
        ) {
            Text(
                text = "#$index · Lived ${TokenLifecycleLog.formatDuration(entry.livedMs)}",
                style = MaterialTheme.typography.titleSmall,
            )
            MetricRow("Source", entry.source.replace('_', ' '))
            MetricRow("Obtained", TokenLifecycleLog.format(entry.obtainedAtEpochMillis))
            MetricRow("Replaced", TokenLifecycleLog.format(entry.replacedAtEpochMillis))
            MetricRow(
                "Configured TTL",
                if (entry.configuredLifetimeSeconds > 0L) {
                    TokenLifecycleLog.formatDuration(entry.configuredLifetimeSeconds * 1000L)
                } else {
                    "—"
                },
            )
        }
    }
}

@Composable
private fun RefreshSessionSection(session: SessionDiagnostics) {
    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xs)) {
        Text(
            text = "Refresh token session",
            style = MaterialTheme.typography.titleMedium,
        )
        MetricRow("Present", yesNo(session.hasRefreshToken))
        MetricRow(
            "Session started",
            when {
                session.refreshSessionStartedAtEpochMillis <= 0L ->
                    "Unknown — sign in again to start the clock"
                else -> TokenLifecycleLog.format(session.refreshSessionStartedAtEpochMillis)
            },
        )
        MetricRow(
            "Session age",
            when {
                session.refreshSessionAgeMs < 0L -> "—"
                else -> TokenLifecycleLog.formatDuration(session.refreshSessionAgeMs)
            },
        )
        MetricRow("Fingerprint", session.refreshFingerprint ?: "—")
        MetricRow(
            "JWT auth_time",
            session.jwtAuthTimeEpochMillis?.let { TokenLifecycleLog.format(it) } ?: "—",
        )
        MetricRow(
            "JWT exp",
            session.jwtExpEpochMillis?.let { TokenLifecycleLog.format(it) } ?: "—",
        )

        Spacer(modifier = Modifier.height(GoodHiveSpacing.sm))
        Text(
            text = "Previous refresh session",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        val previousRefresh = session.previousRefreshSession
        if (previousRefresh == null) {
            Text(
                text = "No completed refresh session yet — recorded when Hive issues a new refresh token or you log out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            MetricRow("Lasted", TokenLifecycleLog.formatDuration(previousRefresh.livedMs))
            MetricRow("Started", TokenLifecycleLog.format(previousRefresh.startedAtEpochMillis))
            MetricRow("Ended", TokenLifecycleLog.format(previousRefresh.endedAtEpochMillis))
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = GoodHiveSpacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(end = GoodHiveSpacing.md),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"
