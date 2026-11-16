package tracker.core.parsers

import tracker.core.Paise
import java.time.DateTimeException
import java.time.LocalDate
import java.util.regex.Pattern

/**
 * Shared, issuer-independent extraction helpers: amounts, dates and card digits.
 * Everything is strict: a value that is not understood yields null, never a guess.
 * Amounts are parsed with integer maths only.
 */
object SmsText {

    private val WHITESPACE = Regex("\\s+")

    /** Collapse line breaks / runs of spaces so templates can be written as one line. */
    fun normalise(body: String): String =
        body.replace('\u00A0', ' ').trim().replace(WHITESPACE, " ")

    // ------------------------------------------------------------ amounts

    /** Digits with optional Indian (1,25,000) or western (125,000) grouping and up to 2 decimals. */
    const val NUMBER = "[0-9](?:[0-9,]*[0-9])?(?:\\.[0-9]{1,2})?"

    /** Currency marker as banks write it: "Rs.", "Rs", "INR", "₹". */
    const val CURRENCY = "(?:Rs\\.?|INR|₹)"

    /** Regex fragment capturing an amount into the named group [group]. */
    fun amount(group: String): String = "$CURRENCY ?(?<$group>$NUMBER)"

    private val AMOUNT = Pattern.compile(
        "^(?:([0-9]{1,3}(?:,[0-9]{2})*,[0-9]{3})|([0-9]{1,3}(?:,[0-9]{3})+)|([0-9]+))(?:\\.([0-9]{1,2}))?$",
    )

    /** "1,25,000.50" -> Paise(12500050). Null for anything malformed (bad grouping, 3 decimals, ...). */
    fun parseAmount(raw: String): Paise? {
        val m = AMOUNT.matcher(raw.trim())
        if (!m.matches()) return null
        val whole = (m.group(1) ?: m.group(2) ?: m.group(3) ?: return null).replace(",", "")
        if (whole.length > 12) return null
        val fraction = m.group(4) ?: ""
        val paise = when (fraction.length) {
            0 -> 0L
            1 -> fraction.toLong() * 10L
            else -> fraction.toLong()
        }
        return Paise(whole.toLong() * 100L + paise)
    }

    private val ANY_AMOUNT = Pattern.compile("$CURRENCY ?($NUMBER)", Pattern.CASE_INSENSITIVE)

    /** Every currency-marked amount in the text, in order. Malformed ones are skipped. */
    fun findAmounts(body: String): List<Paise> {
        val out = ArrayList<Paise>()
        val m = ANY_AMOUNT.matcher(body)
        while (m.find()) parseAmount(m.group(1))?.let { out.add(it) }
        return out
    }

    // -------------------------------------------------------------- dates

    /** Fragment for the date shapes understood by [parseDate], captured into [group]. */
    fun date(group: String): String =
        "(?<$group>[0-9]{4}-[0-9]{2}-[0-9]{2}" +
            "|[0-9]{1,2}[-/.][0-9]{1,2}[-/.][0-9]{2,4}" +
            "|[0-9]{1,2}[- /]?[A-Za-z]{3,9}[- /,]{0,2}[0-9]{2,4})(?![0-9])"

    private val ISO = Pattern.compile("^([0-9]{4})-([0-9]{2})-([0-9]{2})$")
    private val NUMERIC = Pattern.compile("^([0-9]{1,2})[-/.]([0-9]{1,2})[-/.]([0-9]{4}|[0-9]{2})$")
    private val NAMED = Pattern.compile("^([0-9]{1,2})[- /]?([A-Za-z]{3,9})[- /,]{0,2}([0-9]{4}|[0-9]{2})$")
    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

    private fun month(name: String): Int? {
        val n = name.lowercase()
        if (n == "sept") return 9
        val i = MONTHS.indexOfFirst { it == n || (n.length == 3 && it.startsWith(n)) }
        return if (i >= 0) i + 1 else null
    }

    private fun year(text: String): Int = if (text.length == 2) 2000 + text.toInt() else text.toInt()

    private fun build(y: Int, m: Int, d: Int): LocalDate? =
        if (y < 2000 || y > 2099) null else try {
            LocalDate.of(y, m, d)
        } catch (e: DateTimeException) {
            null
        }

    /**
     * Day-first only (Indian convention): dd-mm-yy, dd-mm-yyyy, dd/mm/yy, dd-Mon-yy, dd Mon yyyy,
     * plus ISO yyyy-mm-dd. Two-digit years mean 20yy. Null if the text is not a real date.
     */
    fun parseDate(raw: String): LocalDate? {
        val t = raw.trim()
        var m = ISO.matcher(t)
        if (m.matches()) return build(m.group(1).toInt(), m.group(2).toInt(), m.group(3).toInt())
        m = NUMERIC.matcher(t)
        if (m.matches()) return build(year(m.group(3)), m.group(2).toInt(), m.group(1).toInt())
        m = NAMED.matcher(t)
        if (m.matches()) {
            val mon = month(m.group(2)) ?: return null
            return build(year(m.group(3)), mon, m.group(1).toInt())
        }
        return null
    }

    // -------------------------------------------------------- card digits

    /** Fragment for "x1234", "XX1234", "**1234", "ending 1234", "no. XX1234", plain "1234". */
    fun card(group: String): String =
        "(?:(?:no\\.?|number) ?)?(?:ending(?: in| with)? )?(?:[xX*]{1,12}[- ]?)?(?<$group>[0-9]{4})(?![0-9])"

    private val LAST4 = Pattern.compile(
        "(?:ending(?: in| with)? |[xX*]{1,12}[- ]?)([0-9]{4})(?![0-9])",
        Pattern.CASE_INSENSITIVE,
    )

    /** Distinct masked card endings mentioned in the text ("x1234", "XX1234", "ending 1234"). */
    fun findLast4(body: String): List<String> {
        val out = LinkedHashSet<String>()
        val m = LAST4.matcher(body)
        while (m.find()) out.add(m.group(1))
        return out.toList()
    }
}
