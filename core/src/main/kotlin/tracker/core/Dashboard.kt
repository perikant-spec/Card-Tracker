package tracker.core

import java.time.LocalDate
import java.time.YearMonth

data class CardSummary(
    val card: CardConfig,
    val balance: CardBalance,
    val status: CardStatus,
    /** Bank figure minus ledger-explained figure for the latest statement; null without two statements. */
    val variance: Paise?,
    val latestStatement: Statement?,
    val dueDate: LocalDate?,
    /** Statement notices seen in SMS and still waiting for the user to confirm. */
    val openProposals: Int,
)

data class DashboardSummary(
    val cards: List<CardSummary>,
    val totalBilled: Paise,
    val totalUnbilled: Paise,
    val totalPending: Paise,
    val totalOutstanding: Paise,
    val worstStatus: CardStatus,
    val openReviewCount: Int,
)

/** Higher is worse. NO_ANCHOR is worst: there is no bank figure at all behind the number. */
val CardStatus.severity: Int
    get() = when (this) {
        CardStatus.RECONCILED -> 0
        CardStatus.VARIANCE -> 1
        CardStatus.STALE -> 2
        CardStatus.NO_ANCHOR -> 3
    }

object Dashboard {

    /** Day-of-month clamped to the month length (statement day 31 in February -> 28/29). */
    fun clampDay(month: YearMonth, day: Int): LocalDate =
        month.atDay(Math.min(Math.max(day, 1), month.lengthOfMonth()))

    /** Most recent date on or before [date] that falls on the card's statement day. */
    fun lastStatementDayOnOrBefore(date: LocalDate, statementDay: Int): LocalDate {
        val thisMonth = clampDay(YearMonth.from(date), statementDay)
        return if (!thisMonth.isAfter(date)) thisMonth else clampDay(YearMonth.from(date).minusMonths(1), statementDay)
    }

    /** First date strictly after [after] that falls on [dueDay]. */
    fun nextDueDate(after: LocalDate, dueDay: Int): LocalDate {
        val thisMonth = clampDay(YearMonth.from(after), dueDay)
        return if (thisMonth.isAfter(after)) thisMonth else clampDay(YearMonth.from(after).plusMonths(1), dueDay)
    }

    /**
     * NO_ANCHOR   no confirmed statement for the card.
     * STALE       the latest confirmed statement is more than a month (+grace) old, or a newer
     *             statement notice is waiting for confirmation.
     * VARIANCE    the latest statement does not equal previous statement + ledger activity.
     * RECONCILED  otherwise (a single opening statement counts as reconciled: it is the anchor).
     */
    fun summariseCard(
        card: CardConfig,
        txns: List<Txn>,
        statements: List<Statement>,
        proposals: List<StatementProposal>,
        today: LocalDate,
        graceDays: Long,
    ): CardSummary {
        val mine = statements.filter { it.cardId == card.id }.sortedBy { it.statementDate }
        val myProposals = proposals.filter { it.cardId == card.id }
        val latest = mine.lastOrNull()
        if (latest == null) {
            return CardSummary(
                card = card,
                balance = Ledger.balanceWithoutAnchor(card.id, txns),
                status = CardStatus.NO_ANCHOR,
                variance = null,
                latestStatement = null,
                dueDate = null,
                openProposals = myProposals.size,
            )
        }
        val previous = if (mine.size >= 2) mine[mine.size - 2] else null
        val recon = if (previous != null) Reconciler.reconcile(previous, latest, txns) else null
        val overdue = today.isAfter(latest.statementDate.plusMonths(1).plusDays(graceDays))
        val newerNotice = myProposals.any { it.statementDate.isAfter(latest.statementDate) }
        val status = when {
            overdue || newerNotice -> CardStatus.STALE
            recon != null && recon.variance != Paise.ZERO -> CardStatus.VARIANCE
            else -> CardStatus.RECONCILED
        }
        return CardSummary(
            card = card,
            balance = Ledger.balance(latest, txns),
            status = status,
            variance = recon?.variance,
            latestStatement = latest,
            dueDate = latest.dueDate ?: nextDueDate(latest.statementDate, card.dueDay),
            openProposals = myProposals.size,
        )
    }

    fun summarise(
        cards: List<CardConfig>,
        txns: List<Txn>,
        statements: List<Statement>,
        proposals: List<StatementProposal>,
        openReviewCount: Int,
        today: LocalDate,
        graceDays: Long,
    ): DashboardSummary {
        val rows = cards.map { summariseCard(it, txns, statements, proposals, today, graceDays) }
        var billed = Paise.ZERO
        var unbilled = Paise.ZERO
        var pending = Paise.ZERO
        for (r in rows) {
            billed += r.balance.billed
            unbilled += r.balance.unbilled
            pending += r.balance.pending
        }
        val worst = rows.map { it.status }.maxByOrNull { it.severity } ?: CardStatus.NO_ANCHOR
        return DashboardSummary(rows, billed, unbilled, pending, billed + unbilled, worst, openReviewCount)
    }
}
