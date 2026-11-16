package tracker.app.ui

import tracker.core.CardStatus
import tracker.core.DashboardSummary

/**
 * First-run guidance shown on the dashboard while something is still unfinished.
 * Pure function (unit-tested): it only looks at the dashboard summary and whether SMS access is granted.
 * Returns no steps once everything is set up, and the dashboard then shows no checklist at all.
 */
object SetupChecklist {
    fun steps(summary: DashboardSummary, smsGranted: Boolean): List<String> {
        val steps = ArrayList<String>()
        if (!smsGranted) {
            steps.add("Allow SMS access in Settings so bank messages can be read.")
        }
        val withoutDigits = summary.cards.count { it.card.last4.isBlank() }
        if (withoutDigits > 0) {
            steps.add("Enter the last four digits for $withoutDigits card(s) in Settings. Cards without them are not tracked.")
        }
        val withoutAnchor = summary.cards.count { it.card.last4.isNotBlank() && it.status == CardStatus.NO_ANCHOR }
        if (withoutAnchor > 0) {
            steps.add("Enter the latest statement for $withoutAnchor card(s) so their totals have a bank figure behind them.")
        }
        return steps
    }
}
