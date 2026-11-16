package tracker.core

import java.time.temporal.ChronoUnit

sealed interface MatchResult {
    data class Matched(val pending: Txn) : MatchResult
    data object None : MatchResult
    /** More than one plausible pending row. Never guess; send to the review queue. */
    data class Ambiguous(val candidates: List<Txn>) : MatchResult
}

object PendingMatcher {
    const val WINDOW_DAYS = 5L

    private fun norm(s: String?) = s?.lowercase()?.filter { it.isLetterOrDigit() } ?: ""

    /**
     * Match an incoming settled purchase against PENDING rows.
     * 1. Reference number, if both sides have one.
     * 2. Otherwise same card, exact amount, same normalised merchant, within WINDOW_DAYS.
     * Two genuine same-amount purchases are not collapsed: two candidates => Ambiguous.
     */
    fun match(incoming: Txn, pending: List<Txn>): MatchResult {
        val open = pending.filter { it.state == TxnState.PENDING && it.cardId == incoming.cardId }

        if (incoming.ref != null) {
            val byRef = open.filter { it.ref == incoming.ref }
            if (byRef.size == 1) return MatchResult.Matched(byRef.single())
            if (byRef.size > 1) return MatchResult.Ambiguous(byRef)
        }

        val candidates = open.filter {
            it.amount == incoming.amount &&
                norm(it.merchant) == norm(incoming.merchant) &&
                Math.abs(ChronoUnit.DAYS.between(it.date, incoming.date)) <= WINDOW_DAYS
        }
        return when (candidates.size) {
            0 -> MatchResult.None
            1 -> MatchResult.Matched(candidates.single())
            else -> MatchResult.Ambiguous(candidates)
        }
    }
}
