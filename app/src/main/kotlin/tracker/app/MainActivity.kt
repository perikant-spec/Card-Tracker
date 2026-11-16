package tracker.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CancellationException
import tracker.app.ui.AppNav
import tracker.app.ui.CardTrackerTheme
import tracker.app.ui.FatalScreen
import tracker.app.ui.LockScreen
import java.time.LocalDate

/** Survives rotation, so turning the phone does not ask for the fingerprint again. */
class LockState : ViewModel() {
    val unlocked = mutableStateOf(false)
    val message = mutableStateOf<String?>(null)
}

class MainActivity : FragmentActivity() {
    private lateinit var lock: LockState
    private var prompting = false

    /** True only while a system file picker opened from Settings is in front (see SettingsScreen). */
    private var suppressRelock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no preview in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        lock = ViewModelProvider(this).get(LockState::class.java)
        val graph = (application as CardTrackerApp).graph

        setContent {
            CardTrackerTheme {
                if (lock.unlocked.value) {
                    Unlocked(graph) { suppress -> suppressRelock = suppress }
                } else {
                    LockScreen(lock.message.value) { authenticate() }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!lock.unlocked.value && !prompting) authenticate()
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app locks it again (rotation and our own file pickers excepted).
        if (!isChangingConfigurations && !suppressRelock) lock.unlocked.value = false
    }

    private fun authenticate() {
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No bypass: without a screen lock the ledger stays closed.
            lock.message.value = "Set up a screen lock (PIN, pattern or password) on this phone, then come back."
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                lock.message.value = null
                lock.unlocked.value = true
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                lock.message.value = errString.toString()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock card tracker")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompting = true
        BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback).authenticate(info)
    }
}

@Composable
private fun Unlocked(graph: AppGraph, suppressRelock: (Boolean) -> Unit) {
    var failure by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // First touch of the encrypted database; also queues authorisations that never settled.
        try {
            graph.repository.write { it.expireStalePendings(LocalDate.now()) }
            ready = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = "Error type: " + e.javaClass.simpleName
        } catch (e: LinkageError) {
            failure = "The database library could not be loaded (" + e.javaClass.simpleName + ")."
        }
    }

    val problem = failure
    when {
        problem != null -> FatalScreen(problem)
        ready -> AppNav(graph, suppressRelock)
        else -> LockScreen("Opening the ledger…") {}
    }
}
