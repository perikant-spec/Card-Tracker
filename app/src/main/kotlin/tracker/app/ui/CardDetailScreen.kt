package tracker.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tracker.core.Statement
import tracker.core.Txn
import tracker.core.TxnState
import java.time.LocalDate

@Composable
fun CardDetailScreen(
    viewModel: CardDetailViewModel,
    onBack: () -> Unit,
    onStatement: (proposalId: String?) -> Unit,
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    var deleteDate by remember { mutableStateOf<LocalDate?>(null) }

    Scaffold(topBar = { ScreenBar(detail?.let { Format.cardTitle(it.summary.card) } ?: "Card", onBack) }) { padding ->
        val current = detail
        if (current == null) {
            Column(modifier = Modifier.padding(padding)) { Loading() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Column {
                    StatusBlock(current.summary.status, StatusUi.label(current.summary.status, current.summary.variance))
                    MoneyLine("Billed", current.summary.balance.billed)
                    MoneyLine("Unbilled", current.summary.balance.unbilled)
                    MoneyLine("Pending, not in the total", current.summary.balance.pending)
                    MoneyLine("Outstanding", current.summary.balance.outstanding)
                    LabelValue("Due date", Format.date(current.summary.dueDate))
                    Button(onClick = { onStatement(null) }, modifier = Modifier.padding(top = 8.dp)) { Text("Enter a statement") }
                }
            }

            if (current.proposals.isNotEmpty()) {
                item { SectionTitle("Statement notices from SMS (not applied)") }
                items(current.proposals, key = { "p" + it.id }) { proposal ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            LabelValue("Statement date (assumed)", Format.date(proposal.statementDate))
                            MoneyLine("Total due", proposal.totalDue)
                            LabelValue("Due date", Format.date(proposal.dueDate))
                            Row {
                                TextButton(onClick = { onStatement(proposal.id) }) { Text("Review and confirm") }
                                TextButton(onClick = { viewModel.rejectProposal(proposal.id) }) { Text("Discard") }
                            }
                        }
                    }
                }
            }

            item { SectionTitle("Statement history") }
            if (current.statements.isEmpty()) item { Text("No statement entered yet. This card has no anchor.") }
            items(current.statements, key = { "s" + it.statementDate.toEpochDay() }) { statement ->
                StatementRow(statement) { deleteDate = statement.statementDate }
            }

            item { SectionTitle("Transactions") }
            if (current.txns.isEmpty()) item { Text("Nothing recorded yet.") }
            items(current.txns, key = { "t" + it.id }) { txn -> TxnRow(txn) { viewModel.undoAdjustment(txn.id) } }
        }
    }

    val pendingDelete = deleteDate
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { deleteDate = null },
            title = { Text("Delete this statement?") },
            text = { Text("The statement dated " + Format.date(pendingDelete) + " will be removed. Transactions are not touched.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteStatement(pendingDelete); deleteDate = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteDate = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatementRow(statement: Statement, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            LabelValue("Statement date", Format.date(statement.statementDate))
            MoneyLine("Total due", statement.totalDue)
            statement.minDue?.let { MoneyLine("Minimum due", it) }
            LabelValue("Due date", Format.date(statement.dueDate))
            TextButton(onClick = onDelete) { Text("Delete") }
        }
    }
}

@Composable
private fun TxnRow(txn: Txn, onUndo: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(txn.merchant ?: txn.note ?: StatusUi.typeLabel(txn.type), style = MaterialTheme.typography.bodyLarge, maxLines = 2)
                    Text(Format.date(txn.date) + " · " + StatusUi.typeLabel(txn.type), style = MaterialTheme.typography.bodySmall)
                }
                Text(StatusUi.signedAmount(txn), style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // State chip: display only.
                FilterChip(selected = txn.state == TxnState.POSTED, onClick = {}, label = { Text(StatusUi.stateLabel(txn)) })
                if (txn.state == TxnState.ADJUSTMENT) {
                    TextButton(onClick = onUndo) { Text("Undo adjustment") }
                }
            }
        }
    }
}
