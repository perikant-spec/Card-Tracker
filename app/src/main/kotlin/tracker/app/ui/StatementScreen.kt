package tracker.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tracker.core.Paise
import tracker.core.Statement

/**
 * Enter a statement by hand, or confirm one proposed from an SMS. Shows the variance against
 * the ledger before and after confirming. A variance is never fixed silently: the user either
 * goes looking for the missing message or adds an explicit, undoable adjustment.
 */
@Composable
fun StatementScreen(viewModel: StatementViewModel, onBack: () -> Unit, onReview: () -> Unit) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    Scaffold(topBar = { ScreenBar("Statement", onBack) }) { padding ->
        val current = form
        if (current == null) {
            Column(modifier = Modifier.padding(padding)) { Loading() }
            return@Scaffold
        }
        val proposal = current.proposal
        var dateText by rememberSaveable { mutableStateOf(Format.dateInput(proposal?.statementDate ?: current.suggestedDate)) }
        var totalText by rememberSaveable { mutableStateOf(proposal?.totalDue?.let { plain(it) } ?: "") }
        var minText by rememberSaveable { mutableStateOf(proposal?.minDue?.let { plain(it) } ?: "") }
        var dueText by rememberSaveable { mutableStateOf(Format.dateInput(proposal?.dueDate)) }
        var formError by rememberSaveable { mutableStateOf("") }

        fun build(): Statement? {
            val date = Format.parseDate(dateText)
            val total = Format.parseMoney(totalText, signed = true)
            val min = if (minText.isBlank()) null else Format.parseMoney(minText)
            val due = if (dueText.isBlank()) null else Format.parseDate(dueText)
            formError = when {
                date == null -> "Statement date must look like 15-11-2026."
                total == null -> "Total due must be an amount such as 10,000.00."
                minText.isNotBlank() && min == null -> "Minimum due is not a valid amount."
                dueText.isNotBlank() && due == null -> "Due date must look like 05-12-2026."
                else -> ""
            }
            if (date == null || total == null || formError.isNotEmpty()) return null
            return Statement(current.card.id, date, total, min, due)
        }

        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(Format.cardTitle(current.card), style = MaterialTheme.typography.titleLarge)
            if (proposal != null) {
                Text(
                    "Figures below were read from a statement SMS. The statement date is assumed from the card's statement day; correct anything that is wrong before confirming.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val confirmed = outcome?.confirmed == true
            OutlinedTextField(
                value = dateText, onValueChange = { dateText = it }, enabled = !confirmed, singleLine = true,
                label = { Text("Statement date (dd-mm-yyyy)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = totalText, onValueChange = { totalText = it }, enabled = !confirmed, singleLine = true,
                label = { Text("Total amount due (₹)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = minText, onValueChange = { minText = it }, enabled = !confirmed, singleLine = true,
                label = { Text("Minimum amount due (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = dueText, onValueChange = { dueText = it }, enabled = !confirmed, singleLine = true,
                label = { Text("Payment due date (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            if (formError.isNotEmpty()) Text(formError, color = MaterialTheme.colorScheme.error)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (!confirmed) {
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    OutlinedButton(onClick = { build()?.let { viewModel.check(it) } }) { Text("Check against ledger") }
                    Button(onClick = { build()?.let { viewModel.confirm(it) } }, modifier = Modifier.padding(start = 8.dp)) {
                        Text("Confirm statement")
                    }
                }
            }

            outcome?.let { result -> OutcomeCard(result, viewModel, onReview, onBack) }
        }
    }
}

/** Plain digits for an editable field: "10000.00". */
private fun plain(amount: Paise): String {
    val abs = Math.abs(amount.value)
    return (if (amount.value < 0) "-" else "") + (abs / 100).toString() + "." + (abs % 100).toString().padStart(2, '0')
}

@Composable
private fun OutcomeCard(result: StatementOutcome, viewModel: StatementViewModel, onReview: () -> Unit, onBack: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(if (result.confirmed) "Statement confirmed" else "Not saved yet", style = MaterialTheme.typography.titleMedium)
            val recon = result.recon
            if (recon == null) {
                Text("There is no earlier statement for this card, so this one becomes the opening anchor. Nothing to compare against.")
            } else {
                MoneyLine("Bank's total due", recon.actual)
                MoneyLine("Ledger explains", recon.expected)
                MoneyLine("Variance (bank minus ledger)", recon.variance)
                if (recon.variance == Paise.ZERO) {
                    Text("The ledger reconciles with this statement.")
                } else {
                    Text(
                        if (recon.variance.value > 0) "The bank says more is owed than the messages explain (missed purchase, fee or interest?)."
                        else "The bank says less is owed than the messages explain (missed payment or refund, or a duplicate row?).",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (result.confirmed) {
                val variance = recon?.variance
                if (variance != null && variance != Paise.ZERO && result.adjustmentId == null) {
                    Row {
                        TextButton(onClick = onReview) { Text("Find missing") }
                        TextButton(onClick = { viewModel.addVarianceAdjustment() }) {
                            Text("Add adjustment of " + Format.inr(variance))
                        }
                    }
                }
                if (result.adjustmentId != null) {
                    Text("An explicit adjustment row was added on the statement date.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.undoAdjustment() }) { Text("Undo adjustment") }
                }
                Button(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("Done") }
            }
        }
    }
}
