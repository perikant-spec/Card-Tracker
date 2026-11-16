package tracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tracker.core.CardSummary
import tracker.core.DashboardSummary
import tracker.core.Paise

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onCard: (String) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            ScreenBar("Outstanding") {
                TextButton(onClick = onReview) { Text("Review (" + (summary?.openReviewCount ?: 0) + ")") }
                TextButton(onClick = onSettings) { Text("Settings") }
            }
        },
    ) { padding ->
        val current = summary
        if (current == null) {
            Column(modifier = Modifier.padding(padding)) { Loading() }
        } else {
            DashboardContent(current, onCard, onReview, onSettings, Modifier.padding(padding))
        }
    }
}

@Composable
private fun DashboardContent(
    summary: DashboardSummary,
    onCard: (String) -> Unit,
    onReview: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier,
) {
    // Evaluated on every composition, not remembered, so a permission granted in Settings is noticed on return.
    val setupSteps = SetupChecklist.steps(summary, smsGranted(LocalContext.current))
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (setupSteps.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Finish setting up", style = MaterialTheme.typography.titleMedium)
                        for (step in setupSteps) {
                            Text("• " + step, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                        TextButton(onClick = onSettings) { Text("Open Settings") }
                    }
                }
            }
        }
        item {
            // Confidence banner: always visible, driven by the worst card.
            StatusBlock(summary.worstStatus, StatusUi.banner(summary), Modifier.fillMaxWidth())
        }
        item {
            Column {
                Text("Total outstanding", style = MaterialTheme.typography.titleMedium)
                Text(Format.inr(summary.totalOutstanding), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                MoneyLine("Billed (statement dues left)", summary.totalBilled)
                MoneyLine("Unbilled (since statements)", summary.totalUnbilled)
                MoneyLine("Pending, not in the total", summary.totalPending)
                if (summary.openReviewCount > 0) {
                    TextButton(onClick = onReview) { Text(summary.openReviewCount.toString() + " item(s) need review") }
                }
            }
        }
        items(summary.cards, key = { it.card.id }) { card -> CardRow(card, onCard) }
    }
}

@Composable
private fun CardRow(summary: CardSummary, onCard: (String) -> Unit) {
    Card(onClick = { onCard(summary.card.id) }, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Format.cardTitle(summary.card), style = MaterialTheme.typography.titleMedium)
                StatusBlock(summary.status, StatusUi.label(summary.status, summary.variance))
            }
            MoneyLine("Billed", summary.balance.billed)
            MoneyLine("Unbilled", summary.balance.unbilled)
            if (summary.balance.pending != Paise.ZERO) MoneyLine("Pending", summary.balance.pending)
            LabelValue("Due date", Format.date(summary.dueDate))
            if (summary.openProposals > 0) {
                Text("Statement notice waiting for confirmation", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
