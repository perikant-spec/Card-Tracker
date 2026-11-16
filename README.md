# card-tracker

Private Android tracker for outstanding balance across six credit cards
(HDFC, ICICI, Scapia, Kiwi, SBI, Axis). Reads bank SMS locally. No network.

## Build order (do not skip ahead)

1. `core` engine: money, ledger states, billed/unbilled maths, reconciliation, matching. **Written; unit tests written but not yet run** (the authoring sandbox could not reach Maven Central). Run `gradle :core:test` first.
2. SMS fixtures: export your real messages into `fixtures/real/` (git-ignored). Redacted samples go in `fixtures/sample/`.
3. Issuer parsers, one per bank, each tested against real fixtures. Unknown formats go to the review queue.
4. Persistence (Room, encrypted) as an adapter over `core`.
5. Android shell: SMS receiver + inbox backfill, permissions.
6. Encrypted backup/restore.
7. Dashboard UI.

## Rules the engine enforces

- Amounts are integer paise.
- Billed = last statement total due minus payments after the statement date.
- Unbilled = purchases + fees after the statement date, net of refunds.
- Pending authorisations are shown separately, not in the headline.
- Same-amount pending rows are never merged by guess: they become Ambiguous.
- Reconciliation reports variance against the bank's figure and never edits the ledger.
  Corrections are explicit ADJUSTMENT rows.

## Run tests

    gradle :core:test
