package tracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown until the biometric / device-credential prompt succeeds. Displays no ledger data. */
@Composable
fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Card tracker is locked", style = MaterialTheme.typography.headlineSmall)
            if (message != null) {
                Text(message, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = onUnlock, modifier = Modifier.padding(top = 24.dp)) { Text("Unlock") }
        }
    }
}

@Composable
fun FatalScreen(message: String) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
            Text("The ledger could not be opened", style = MaterialTheme.typography.headlineSmall)
            Text(message, modifier = Modifier.padding(top = 12.dp))
        }
    }
}
