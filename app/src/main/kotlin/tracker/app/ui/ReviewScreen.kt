package tracker.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tracker.core.CardConfig
import tracker.core.Paise
import tracker.core.ReviewKind
import tracker.core.Txn
import tracker.core.TxnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Everything the engine refused to decide: unrecognised messages from allowlisted senders,
 * ambiguous matches, possible duplicates and stale authorisations. Message text is shown here,
 * on the device only, and is wiped from storage as soon as the item is resolved or dismissed.
 */
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onBack: () -> Unit) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var classifying by remember { mutableStateOf<ReviewRow?>(null) }

    Scaffold(topBar = { ScreenBar("Review queue", onBack) }) { padding ->
        val current = data
        if (current == null) {
            Column(modifier = Modifier.padding(padding)) { Loading() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            if (current.rows.isEmpty()) item { Text("Nothing needs review.") }
            items(current.rows, key = { it.item.id }) { row ->
                ReviewCard(row, current.cards, viewModel) { classifying = row }
            }
        }
        val target = classifying
        if (target != null) {
            ClassifyDialog(
                row = target,
                cards = current.cards,
                onDismiss = { classifying = null },
                onSave = { cardId, type, amount, date, merchant ->
                    viewModel.classify(target.item.id, cardId, type, amount, date, merchant)
                    classifying = null
                },
            )
        }
    }
}

private fun describe(txn: Txn): String =
    Format.date(txn.date) + " · " + StatusUi.signedAmount(txn) + " · " + (txn.merchant ?: StatusUi.typeLabel(txn.type)) +
        " · " + StatusUi.stateLabel(txn)

@Composable
private fun ReviewCard(row: ReviewRow, cards: List<CardConfig>, viewModel: ReviewViewModel, onClassify: () -> Unit) {
    val item = row.item
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(StatusUi.kindLabel(item.kind), style = MaterialTheme.typography.titleMedium)
            item.reason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            cards.firstOrNull { it.id == item.cardId }?.let { LabelValue("Card", Format.cardTitle(it)) }
            item.sender?.let { LabelValue("Sender", it) }
            item.receivedAtMs?.let {
                LabelValue("Received", Format.date(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()))
            }
            item.body?.let { Text(it, modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyMedium) }
            item.proposed?.let { LabelValue("Read as", describe(it)) }
            row.subject?.let { LabelValue("Transaction", describe(it)) }

            if (row.candidates.isNotEmpty()) {
                Text("Could belong to:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                for (candidate in row.candidates) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(describe(candidate), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        if (item.kind == ReviewKind.AMBIGUOUS_MATCH) {
                            TextButton(onClick = { viewModel.link(item.id, candidate.id) }) { Text("Link") }
                        }
                    }
                }
            }

            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                when (item.kind) {
                    ReviewKind.STALE_PENDING -> {
                        TextButton(onClick = { viewModel.resolveStale(item.id, post = true) }) { Text("It was charged") }
                        TextButton(onClick = { viewModel.resolveStale(item.id, post = false) }) { Text("It dropped off") }
                    }
                    ReviewKind.POSSIBLE_DUPLICATE ->
                        TextButton(onClick = { viewModel.applyAsNew(item.id) }) { Text("Not a duplicate, add it") }
                    ReviewKind.AMBIGUOUS_MATCH, ReviewKind.AMBIGUOUS_REFUND ->
                        TextButton(onClick = { viewModel.applyAsNew(item.id) }) { Text("Add as separate row") }
                    ReviewKind.UNPARSED ->
                        if (item.proposed != null) {
                            TextButton(onClick = { viewModel.applyAsNew(item.id) }) { Text("Add as read") }
                        }
                }
                if (item.kind != ReviewKind.STALE_PENDING) TextButton(onClick = onClassify) { Text("Classify manually") }
                TextButton(onClick = { viewModel.dismiss(item.id) }) {
                    Text(if (item.kind == ReviewKind.POSSIBLE_DUPLICATE) "It is a duplicate, dismiss" else "Dismiss")
                }
            }
        }
    }
}

@Composable
private fun ClassifyDialog(
    row: ReviewRow,
    cards: List<CardConfig>,
    onDismiss: () -> Unit,
    onSave: (cardId: String, type: TxnType, amount: Paise, date: LocalDate, merchant: String?) -> Unit,
) {
    val item = row.item
    val proposed = item.proposed
    val received = item.receivedAtMs?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now()
    var cardId by remember { mutableStateOf(item.cardId ?: cards.firstOrNull()?.id ?: "") }
    var type by remember { mutableStateOf(proposed?.type ?: TxnType.PURCHASE) }
    var amountText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(Format.dateInput(proposed?.date ?: received)) }
    var merchant by remember { mutableStateOf(proposed?.merchant ?: "") }
    var problem by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Classify manually") },
        text = {
            Column {
                item.body?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (card in cards) {
                        FilterChip(
                            selected = cardId == card.id, onClick = { cardId = card.id },
                            label = { Text(card.issuer) }, modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    for (option in TxnType.values()) {
                        FilterChip(
                            selected = type == option, onClick = { type = option },
                            label = { Text(StatusUi.typeLabel(option)) }, modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                OutlinedTextField(
                    value = amountText, onValueChange = { amountText = it }, singleLine = true,
                    label = { Text("Amount (₹)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, singleLine = true, label = { Text("Date (dd-mm-yyyy)") })
                OutlinedTextField(value = merchant, onValueChange = { merchant = it }, singleLine = true, label = { Text("Merchant / note") })
                if (problem.isNotEmpty()) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val amount = Format.parseMoney(amountText)
                val date = Format.parseDate(dateText)
                if (cardId.isEmpty()) {
                    problem = "Choose a card."
                } else if (amount == null || amount.value <= 0L) {
                    problem = "Enter the amount exactly as in the message."
                } else if (date == null) {
                    problem = "Date must look like 15-11-2026."
                } else {
                    onSave(cardId, type, amount, date, merchant.ifBlank { null })
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
