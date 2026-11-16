package tracker.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tracker.core.CardStatus
import tracker.core.Paise

@Composable
fun CardTrackerTheme(content: @Composable () -> Unit) {
    // minSdk 33, so dynamic colour is always available.
    val context = LocalContext.current
    val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { if (onBack != null) TextButton(onClick = onBack) { Text("Back") } },
        actions = { actions() },
    )
}

@Composable
fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
}

/** Coloured block used for the status badge and the dashboard confidence banner. */
@Composable
fun StatusBlock(status: CardStatus, text: String, modifier: Modifier = Modifier) {
    val container = when (status) {
        CardStatus.RECONCILED -> MaterialTheme.colorScheme.primaryContainer
        CardStatus.VARIANCE -> MaterialTheme.colorScheme.tertiaryContainer
        CardStatus.STALE, CardStatus.NO_ANCHOR -> MaterialTheme.colorScheme.errorContainer
    }
    val content = when (status) {
        CardStatus.RECONCILED -> MaterialTheme.colorScheme.onPrimaryContainer
        CardStatus.VARIANCE -> MaterialTheme.colorScheme.onTertiaryContainer
        CardStatus.STALE, CardStatus.NO_ANCHOR -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = container, contentColor = content)) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun Loading() {
    Column(modifier = Modifier.padding(24.dp)) { Text("Loading…") }
}

@Composable
fun MoneyLine(label: String, amount: Paise) {
    LabelValue(label, Format.inr(amount))
}
