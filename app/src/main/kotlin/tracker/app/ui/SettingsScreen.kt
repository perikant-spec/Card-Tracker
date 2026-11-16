package tracker.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tracker.app.sms.BackfillProgress
import tracker.core.CardConfig

private const val MIN_PASSWORD_LENGTH = 10

private fun smsGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

/**
 * [suppressRelock] is called with true just before a system file picker opens and with false
 * when it returns, so the lock gate does not tear down this screen while a result is pending.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpeningStatement: (cardId: String) -> Unit,
    suppressRelock: (Boolean) -> Unit,
) {
    val data by viewModel.data.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var permissionTick by remember { mutableStateOf(0) }
    val granted = remember(permissionTick) { smsGranted(context) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionTick++ }

    // Backup: the password is typed first and held only in memory until the picker returns.
    var askExportPassword by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        suppressRelock(false)
        if (uri != null && exportPassword.isNotEmpty()) viewModel.export(context.contentResolver, uri, exportPassword.toCharArray())
        exportPassword = ""
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        suppressRelock(false)
        restoreUri = uri
    }

    Scaffold(topBar = { ScreenBar("Settings", onBack) }) { padding ->
        val current = data
        if (current == null) {
            Column(modifier = Modifier.padding(padding)) { Loading() }
            return@Scaffold
        }
        Column(modifier = Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            SectionTitle("SMS permission")
            LabelValue("Read and receive SMS", if (granted) "Granted" else "Not granted")
            Row {
                Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)) }) {
                    Text("Request permission")
                }
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Open app info") }
            }
            HelpCard()

            SectionTitle("Cards")
            Text(
                "Set the last four digits exactly as they appear in the bank's messages. A card with no digits is not tracked.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (card in current.cards) {
                CardEditor(card, onSave = { viewModel.saveCard(it) }, onOpeningStatement = { onOpeningStatement(card.id) })
            }

            SectionTitle("Sender allowlist")
            Text(
                "Only messages whose sender ID contains one of these entries are ever read. Entries are matched as " +
                    "case-insensitive substrings (HDFCBK matches VM-HDFCBK-S); at least 3 characters; separate with commas. " +
                    "The defaults are starting points, not verified facts. Scapia and Kiwi are empty on purpose: run " +
                    "tools/extract_fixtures.py list-senders on your own SMS export and enter what you find.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (card in current.cards) {
                AllowlistEditor(card.issuer, current.allowlist[card.issuer] ?: emptyList()) { viewModel.saveAllowlist(card.issuer, it) }
            }

            SectionTitle("Inbox backfill")
            BackfillSection(current.backfillMonths, progress, granted) { viewModel.runBackfill(it) }

            SectionTitle("Encrypted backup")
            Text(
                "The backup file is encrypted with your password (AES-256-GCM, PBKDF2). There is no way to recover it without the password.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                Button(onClick = { askExportPassword = true }) { Text("Export") }
                OutlinedButton(
                    onClick = {
                        suppressRelock(true)
                        restoreLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Restore") }
            }
        }
    }

    if (askExportPassword) {
        PasswordDialog(
            title = "Backup password",
            note = "Choose a password of at least $MIN_PASSWORD_LENGTH characters. You will need it to restore.",
            confirmTwice = true,
            actionLabel = "Choose file",
            onDismiss = { askExportPassword = false },
            onPassword = { password ->
                askExportPassword = false
                exportPassword = password
                suppressRelock(true)
                exportLauncher.launch("card-tracker-backup.ctbk")
            },
        )
    }

    val pendingRestore = restoreUri
    if (pendingRestore != null) {
        PasswordDialog(
            title = "Restore backup",
            note = "Restoring REPLACES the whole ledger on this phone with the contents of the file.",
            confirmTwice = false,
            actionLabel = "Replace ledger",
            onDismiss = { restoreUri = null },
            onPassword = { password ->
                restoreUri = null
                viewModel.restore(context.contentResolver, pendingRestore, password.toCharArray())
            },
        )
    }
}

@Composable
private fun HelpCard() {
    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("If the app misses messages", style = MaterialTheme.typography.titleSmall)
            Text(
                "1. SMS permission: tap Request permission. Because this app is installed from a file, Android may show " +
                    "the SMS toggle greyed out (\"restricted setting\"). Open app info, tap the three-dot menu at the top " +
                    "right, choose Allow restricted settings, confirm with your screen lock, then grant SMS again.\n" +
                    "2. App info > App battery usage: choose Unrestricted, so messages are processed while the phone sleeps.\n" +
                    "3. App info > turn OFF \"Pause app activity if unused\" (also shown as \"Manage app if unused\"), " +
                    "otherwise Android revokes the SMS permission after a few months without opening the app.\n" +
                    "4. Anything missed can be recovered at any time with Inbox backfill below; it never double counts.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CardEditor(card: CardConfig, onSave: (CardConfig) -> Unit, onOpeningStatement: () -> Unit) {
    var last4 by remember(card) { mutableStateOf(card.last4) }
    var statementDay by remember(card) { mutableStateOf(card.statementDay.toString()) }
    var dueDay by remember(card) { mutableStateOf(card.dueDay.toString()) }
    var problem by remember(card) { mutableStateOf("") }

    Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(Format.cardTitle(card), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = last4, onValueChange = { last4 = it.filter { c -> c in '0'..'9' }.take(4) }, singleLine = true,
                label = { Text("Last 4 digits") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row {
                OutlinedTextField(
                    value = statementDay, onValueChange = { statementDay = it.take(2) }, singleLine = true,
                    label = { Text("Statement day") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = dueDay, onValueChange = { dueDay = it.take(2) }, singleLine = true,
                    label = { Text("Due day") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
            }
            if (problem.isNotEmpty()) Text(problem, color = MaterialTheme.colorScheme.error)
            Row {
                TextButton(onClick = {
                    val sd = Format.dayOfMonth(statementDay)
                    val dd = Format.dayOfMonth(dueDay)
                    if (last4.isNotEmpty() && last4.length != 4) {
                        problem = "Enter all four digits, or leave blank."
                    } else if (sd == null || dd == null) {
                        problem = "Days must be between 1 and 31."
                    } else {
                        problem = ""
                        onSave(card.copy(last4 = last4, statementDay = sd, dueDay = dd))
                    }
                }) { Text("Save") }
                TextButton(onClick = onOpeningStatement) { Text("Opening statement (anchor)") }
            }
        }
    }
}

@Composable
private fun AllowlistEditor(issuer: String, patterns: List<String>, onSave: (String) -> Unit) {
    var text by remember(patterns) { mutableStateOf(patterns.joinToString(", ")) }
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text("$issuer sender IDs") },
            supportingText = { if (patterns.isEmpty()) Text("Empty: no $issuer message is read.") },
        )
        TextButton(onClick = { onSave(text) }) { Text("Save") }
    }
}

@Composable
private fun BackfillSection(savedMonths: Int, progress: BackfillProgress, granted: Boolean, onRun: (Int) -> Unit) {
    var months by remember(savedMonths) { mutableStateOf(savedMonths.toString()) }
    Text(
        "Reads the existing inbox for the chosen number of months. Only sender and date are looked at for every message; " +
            "text is read only for allowlisted senders. Safe to run again: nothing is counted twice.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(modifier = Modifier.padding(top = 6.dp)) {
        OutlinedTextField(
            value = months, onValueChange = { months = it.filter { c -> c in '0'..'9' }.take(3) }, singleLine = true,
            label = { Text("Months back") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = { months.toIntOrNull()?.takeIf { it in 1..120 }?.let(onRun) },
            enabled = granted && !progress.running,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(if (progress.running) "Running…" else "Run backfill") }
    }
    if (!granted) Text("Grant the SMS permission first.", style = MaterialTheme.typography.bodySmall)
    if (progress.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
    progress.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (progress.running || progress.finished) {
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        LabelValue("Inbox messages scanned (sender only)", progress.scanned.toString())
        LabelValue("From allowlisted senders", progress.fromAllowlisted.toString())
        LabelValue("Processed so far", progress.processed.toString())
        LabelValue("New ledger rows", progress.applied.toString())
        LabelValue("Statement notices", progress.proposed.toString())
        LabelValue("Sent to review", progress.queued.toString())
        LabelValue("Already known", progress.duplicates.toString())
        LabelValue("Dropped (OTP, promo, account alerts)", progress.ignored.toString())
        LabelValue("Other cards (not tracked)", progress.untracked.toString())
        if (progress.finished) Text("Backfill finished.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    note: String,
    confirmTwice: Boolean,
    actionLabel: String,
    onDismiss: () -> Unit,
    onPassword: (String) -> Unit,
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(note, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = first, onValueChange = { first = it }, singleLine = true, label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = second, onValueChange = { second = it }, singleLine = true, label = { Text("Repeat password") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
                if (problem.isNotEmpty()) Text(problem, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (first.isEmpty()) {
                    problem = "Enter the password."
                } else if (confirmTwice && first.length < MIN_PASSWORD_LENGTH) {
                    problem = "Use at least $MIN_PASSWORD_LENGTH characters."
                } else if (confirmTwice && first != second) {
                    problem = "The two passwords differ."
                } else {
                    onPassword(first)
                }
            }) { Text(actionLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
