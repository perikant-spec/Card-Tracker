package tracker.core.parsers

import tracker.core.Json
import tracker.core.ParseResult
import tracker.core.Paise
import java.io.File
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Fixture-driven parser test. Reads every .jsonl file in fixtures/sample; one JSON object per line:
 *
 *   {"sender": "...", "date_ms": 1794724200000, "body": "...", "issuer": "HDFC",
 *    "expect": {"result": "parsed" | "ignored" | "unparsed",
 *               "type": "PURCHASE|FEE|PAYMENT|REFUND", "amount_paise": 125000, "last4": "1234",
 *               "auth": false, "reversal": false, "merchant": "...", "date": "2026-11-15", "ref": "...",
 *               "template": "hdfc.spend.amt_spent_at",
 *               "statement": {"total_due_paise": 1000000, "min_due_paise": 50000, "due_date": "2026-12-05"}}}
 *
 * Only the keys present under "expect" are checked. "issuer" selects the template set; the
 * fixture's own sender is used as the allowlist entry, so no sender ID is assumed here.
 * Drop real, redacted samples into fixtures/sample/ to grow this suite; never commit fixtures/real/.
 * Failure messages name the file and line number only, never the message text.
 */
class FixtureHarnessTest {

    private fun sampleDir(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "fixtures/sample")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile
        }
        fail("fixtures/sample not found above " + System.getProperty("user.dir"))
    }

    private data class Case(val where: String, val sender: String, val dateMs: Long, val body: String, val issuer: String, val expect: Map<*, *>)

    private fun cases(): List<Case> {
        val out = ArrayList<Case>()
        val files = sampleDir().listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: emptyList()
        for (file in files) {
            file.readLines(Charsets.UTF_8).forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val where = file.name + ":" + (index + 1)
                val obj = Json.parse(line) as? Map<*, *> ?: fail("$where is not a JSON object")
                out.add(
                    Case(
                        where = where,
                        sender = obj["sender"] as? String ?: fail("$where: sender missing"),
                        dateMs = obj["date_ms"] as? Long ?: fail("$where: date_ms missing"),
                        body = obj["body"] as? String ?: fail("$where: body missing"),
                        issuer = obj["issuer"] as? String ?: fail("$where: issuer missing"),
                        expect = obj["expect"] as? Map<*, *> ?: fail("$where: expect missing"),
                    ),
                )
            }
        }
        return out
    }

    private fun check(where: String, field: String, expected: Any?, actual: Any?, problems: MutableList<String>) {
        if (expected != actual) problems.add("$where: $field differs from the expectation")
    }

    private fun verify(c: Case, problems: MutableList<String>): String? {
        val parser = IssuerParsers.parser(c.issuer, listOf(c.sender)) ?: run {
            problems.add(c.where + ": unknown issuer")
            return null
        }
        val result = parser.parse(c.sender, c.body, Instant.ofEpochMilli(c.dateMs))
        val kind = when (result) {
            is ParseResult.Parsed -> "parsed"
            is ParseResult.Ignored -> "ignored"
            is ParseResult.Unparsed -> "unparsed"
        }
        val e = c.expect
        check(c.where, "result", e["result"], kind, problems)
        if (result !is ParseResult.Parsed) return null
        val d = result.draft
        if (e.containsKey("type")) check(c.where, "type", e["type"], d.type.name, problems)
        if (e.containsKey("amount_paise")) check(c.where, "amount_paise", e["amount_paise"], d.amount.value, problems)
        if (e.containsKey("last4")) check(c.where, "last4", e["last4"], d.cardLast4, problems)
        if (e.containsKey("auth")) check(c.where, "auth", e["auth"], d.isAuthorisation, problems)
        if (e.containsKey("reversal")) check(c.where, "reversal", e["reversal"], d.isReversal, problems)
        if (e.containsKey("merchant")) check(c.where, "merchant", e["merchant"], d.merchant, problems)
        if (e.containsKey("ref")) check(c.where, "ref", e["ref"], d.ref, problems)
        if (e.containsKey("template")) check(c.where, "template", e["template"], d.template, problems)
        if (e.containsKey("date")) check(c.where, "date", (e["date"] as? String)?.let { LocalDate.parse(it) }, d.txnDate, problems)
        val st = e["statement"] as? Map<*, *>
        if (e.containsKey("statement")) {
            val actual = d.statement
            if (st == null || actual == null) {
                check(c.where, "statement", st != null, actual != null, problems)
            } else {
                if (st.containsKey("total_due_paise")) check(c.where, "total_due_paise", st["total_due_paise"], actual.totalDue.value, problems)
                if (st.containsKey("min_due_paise")) check(c.where, "min_due_paise", st["min_due_paise"], actual.minDue?.value, problems)
                if (st.containsKey("due_date")) check(c.where, "due_date", (st["due_date"] as? String)?.let { LocalDate.parse(it) }, actual.dueDate, problems)
            }
        } else if (d.statement != null) {
            problems.add(c.where + ": parsed as a statement but no statement was expected")
        }
        return d.template
    }

    @Test fun `every sample fixture parses as expected`() {
        val all = cases()
        assertTrue(all.isNotEmpty(), "no fixtures found in fixtures/sample")
        val problems = ArrayList<String>()
        for (c in all) verify(c, problems)
        if (problems.isNotEmpty()) fail(problems.size.toString() + " fixture problem(s):\n" + problems.joinToString("\n"))
    }

    @Test fun `every template of every issuer is exercised by at least one sample`() {
        val problems = ArrayList<String>()
        val used = cases().mapNotNull { verify(it, problems) }.toSet()
        val missing = IssuerParsers.TEMPLATES.values.flatten().map { it.name }.filter { it !in used }
        assertTrue(missing.isEmpty(), "templates with no sample message: $missing")
    }

    @Test fun `amounts in fixtures are whole paise`() {
        // The JSON reader rejects fractional numbers outright, so a fixture can never smuggle in a float.
        for (c in cases()) {
            val v = c.expect["amount_paise"]
            if (v != null) assertTrue(v is Long && Paise(v).value == v, c.where)
        }
    }
}
