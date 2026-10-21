package com.gazw89.goodhive.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiveWriteDetailScreen(
    onBack: () -> Unit,
    viewModel: HiveWriteDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val detail = ui.detail

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.title ?: "Hive write") },
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
            when {
                !ui.loaded -> Unit
                detail == null -> Text(
                    text = "This write is no longer in the log.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> {
                    Column(verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.xs)) {
                        MetricRow("Response code", detail.responseCode, emphasizeError = detail.failed)
                        MetricRow("Response time", detail.duration)
                        MetricRow("When", detail.whenLabel)
                        MetricRow("Path", detail.path)
                    }
                    BodyCard(title = "Request", body = detail.requestText, isError = false)
                    BodyCard(title = "Response", body = detail.responseText, isError = detail.responseIsError)
                }
            }
        }
    }
}

@Composable
private fun BodyCard(title: String, body: String, isError: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.padding(GoodHiveSpacing.md),
            verticalArrangement = Arrangement.spacedBy(GoodHiveSpacing.sm),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, emphasizeError: Boolean = false) {
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
            color = if (emphasizeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}
