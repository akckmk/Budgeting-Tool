package com.budgetingtool.app

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.budgetingtool.app.ui.BudgetAppUi
import com.budgetingtool.app.ui.theme.BudgetTheme

class MainActivity : FragmentActivity() {
    private var unlocked by mutableStateOf(false)
    private var backgroundedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Applied before any content is drawn so the recent-apps thumbnail never shows balances.
        if (container.prefs.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            BudgetTheme {
                if (!container.prefs.appLock || unlocked) {
                    BudgetAppUi()
                } else {
                    LockScreen()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Foreground services may only be started while the app is visible.
        com.budgetingtool.app.sync.SyncService.start(this)
        container.sync.connectKnown()
        val away = backgroundedAt > 0 && SystemClock.elapsedRealtime() - backgroundedAt > RELOCK_AFTER_MS
        if (away) unlocked = false
        if (container.prefs.appLock && !unlocked) authenticate()
    }

    override fun onStop() {
        super.onStop()
        backgroundedAt = SystemClock.elapsedRealtime()
    }

    @androidx.compose.runtime.Composable
    private fun LockScreen() {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🔒", style = MaterialTheme.typography.displayMedium)
                Text("Budgeting Tool is locked", style = MaterialTheme.typography.titleLarge)
                Button(onClick = ::authenticate) { Text("Unlock") }
            }
        }
    }

    private fun authenticate() {
        val authenticators = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No screen lock on this device, so there's nothing to check against.
            unlocked = true
            return
        }
        val prompt = BiometricPrompt(
            this,
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlocked = true
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Budgeting Tool")
                .setSubtitle("Your finances stay private")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    private companion object {
        const val RELOCK_AFTER_MS = 60_000L
    }
}
