package com.meshwalk.app.ui.lock

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.meshwalk.app.experimental.DuressManager
import com.meshwalk.app.experimental.DuressUnlockResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BiometricLockViewModel @Inject constructor(
    private val duressManager: DuressManager
) : ViewModel() {
    fun checkDuress(pin: String, onResult: (DuressUnlockResult) -> Unit) {
        viewModelScope.launch {
            onResult(duressManager.checkAndTrigger(pin))
        }
    }
}

/**
 * Full-screen lock gate displayed when fingerprint lock is enabled.
 * Automatically prompts for biometric authentication on first display
 * and provides a manual retry button.
 *
 * Also exposes a "Use PIN" path that, if a duress PIN has been configured,
 * silently wipes local data when entered and then proceeds with unlock.
 */
@Composable
fun BiometricLockScreen(
    onAuthenticated: () -> Unit,
    viewModel: BiometricLockViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showPinDialog by remember { mutableStateOf(false) }

    val promptBiometric = remember(activity) {
        {
            if (activity != null) {
                errorMessage = null
                val executor = ContextCompat.getMainExecutor(activity)
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        onAuthenticated()
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                            errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                            errorCode != BiometricPrompt.ERROR_CANCELED
                        ) {
                            errorMessage = errString.toString()
                        }
                    }

                    override fun onAuthenticationFailed() {
                        errorMessage = "Fingerprint not recognized"
                    }
                }

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock MeshWalk")
                    .setSubtitle("Authenticate to access the app")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build()

                BiometricPrompt(activity, executor, callback).authenticate(promptInfo)
            }
        }
    }

    LaunchedEffect(Unit) { promptBiometric() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "MeshWalk is Locked",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Use your fingerprint to unlock",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            FilledTonalButton(onClick = { promptBiometric() }) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Unlock")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { showPinDialog = true }) {
                Text("Use PIN")
            }
        }
    }

    if (showPinDialog) {
        DuressPinPrompt(
            onDismiss = { showPinDialog = false },
            onSubmit = { pin ->
                viewModel.checkDuress(pin) { result ->
                    showPinDialog = false
                    when (result) {
                        DuressUnlockResult.TRIGGERED -> onAuthenticated()
                        DuressUnlockResult.NOT_APPLICABLE -> errorMessage = "PIN unlock not configured"
                        DuressUnlockResult.NOT_A_DURESS_PIN -> errorMessage = "Incorrect PIN"
                    }
                }
            }
        )
    }
}

@Composable
private fun DuressPinPrompt(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter PIN") },
        text = {
            OutlinedTextField(
                value = pin,
                onValueChange = { pin = it.filter(Char::isDigit) },
                label = { Text("PIN") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(enabled = pin.length >= 4, onClick = { onSubmit(pin) }) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
