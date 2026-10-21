package com.gazw89.goodhive.ui.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gazw89.goodhive.ui.components.bouncyPressable
import com.gazw89.goodhive.ui.theme.GoodHiveMotion
import com.gazw89.goodhive.ui.theme.GoodHiveSpacing
import com.gazw89.goodhive.ui.theme.LocalReducedMotion

@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    viewModel: LoginViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var entered by remember { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current

    LaunchedEffect(uiState.step) {
        if (uiState.step is LoginStep.LoggedIn) onLoggedIn()
    }
    LaunchedEffect(Unit) { entered = true }

    val background = Brush.verticalGradient(
        colors = listOf(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            MaterialTheme.colorScheme.background,
        ),
    )

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(background),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = GoodHiveSpacing.xl),
                verticalArrangement = Arrangement.Center,
            ) {
                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(GoodHiveMotion.entranceTween(reducedMotion)) +
                        slideInVertically(GoodHiveMotion.entranceTween(reducedMotion)) { it / 3 },
                ) {
                    HiveMark()
                }

                Spacer(Modifier.height(GoodHiveSpacing.lg))

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(GoodHiveMotion.entranceTween(reducedMotion, delayMs = 80)) +
                        slideInVertically(GoodHiveMotion.entranceTween(reducedMotion, delayMs = 80)) { it / 3 },
                ) {
                    Column {
                        Text(
                            text = "GoodHive",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(Modifier.height(GoodHiveSpacing.tight))
                        Text(
                            text = "Warm home, one tap away",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(GoodHiveSpacing.xxl))

                AnimatedVisibility(
                    visible = entered,
                    enter = fadeIn(GoodHiveMotion.entranceTween(reducedMotion, delayMs = 160)) +
                        slideInVertically(GoodHiveMotion.entranceTween(reducedMotion, delayMs = 160)) { it / 2 },
                ) {
                    AnimatedContent(
                        targetState = uiState.step,
                        label = "login-step",
                        transitionSpec = {
                            (fadeIn(GoodHiveMotion.entranceTween(reducedMotion, durationMs = 300)) +
                                slideInVertically(GoodHiveMotion.entranceTween(reducedMotion, durationMs = 300)) { it / 6 }) togetherWith
                                (fadeOut(GoodHiveMotion.quickTween(reducedMotion, durationMs = 150)))
                        },
                    ) { step ->
                        when (step) {
                            is LoginStep.Credentials -> CredentialsForm(
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.errorMessage,
                                initialEmail = uiState.savedUsername,
                                initialPassword = uiState.savedPassword,
                                onSubmit = viewModel::submitCredentials,
                            )
                            is LoginStep.SmsCode -> SmsCodeForm(
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.errorMessage,
                                onSubmit = viewModel::submitSmsCode,
                            )
                            is LoginStep.LoggedIn -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HiveMark() {
    Box(
        modifier = Modifier
            .size(76.dp)
            .background(
                Brush.linearGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer),
                ),
                MaterialTheme.shapes.large,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Hexagon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(38.dp),
        )
    }
}

@Composable
private fun highContrastTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    cursorColor = MaterialTheme.colorScheme.primary,
)

/** Button that eases into a slight press-down scale — a small tactile cue instead of a flat tap. */
@Composable
private fun BouncyButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactionSource,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(vertical = GoodHiveSpacing.roomy),
        modifier = modifier.bouncyPressable(interactionSource, pressedScale = 0.96f),
    ) { content() }
}

@Composable
private fun ErrorText(message: String) {
    AnimatedVisibility(
        visible = true,
        enter = fadeIn(GoodHiveMotion.entranceTween(LocalReducedMotion.current, durationMs = 200)) +
            slideInVertically(GoodHiveMotion.entranceTween(LocalReducedMotion.current, durationMs = 200)) { -it / 2 },
        exit = fadeOut() + slideOutVertically(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = GoodHiveSpacing.cozy),
        )
    }
}

@Composable
private fun CredentialsForm(
    isLoading: Boolean,
    errorMessage: String?,
    initialEmail: String,
    initialPassword: String,
    onSubmit: (username: String, password: String) -> Unit,
) {
    var email by remember(initialEmail) { mutableStateOf(initialEmail) }
    var password by remember(initialPassword) { mutableStateOf(initialPassword) }

    Column {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Hive email") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            colors = highContrastTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(GoodHiveSpacing.md))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = highContrastTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        errorMessage?.let { ErrorText(it) }
        Spacer(Modifier.height(GoodHiveSpacing.section))
        LoadingOrButton(isLoading = isLoading, label = "Sign in") { onSubmit(email, password) }
    }
}

@Composable
private fun SmsCodeForm(
    isLoading: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit,
) {
    var code by remember { mutableStateOf("") }

    Column {
        Text(
            text = "Enter the code Hive just texted you",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(GoodHiveSpacing.md))
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("SMS code") },
            singleLine = true,
            shape = MaterialTheme.shapes.small,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = highContrastTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        errorMessage?.let { ErrorText(it) }
        Spacer(Modifier.height(GoodHiveSpacing.section))
        LoadingOrButton(isLoading = isLoading, label = "Verify") { onSubmit(code) }
    }
}

@Composable
private fun LoadingOrButton(isLoading: Boolean, label: String, onClick: () -> Unit) {
    val height by animateDpAsState(targetValue = GoodHiveSpacing.hero, label = "cta-height")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = isLoading, label = "cta-content") { loading ->
            if (loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                BouncyButton(onClick = onClick, enabled = true, modifier = Modifier.fillMaxWidth()) {
                    Text(label)
                }
            }
        }
    }
}
