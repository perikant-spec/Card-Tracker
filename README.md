# card-tracker

Private Android tracker for the total outstanding across six credit cards
(HDFC, ICICI, Scapia, Kiwi, SBI, Axis), split into billed and unbilled.
It reads bank SMS on the phone and builds a ledger locally. Single user, sideloaded,
Pixel 10 / Android 16. **No network access of any kind.**

> **Read `docs/UNVERIFIED.md` before trusting anything.** The Android app has never been
> compiled or run, and every bank SMS template was written without a single real message.

## Layout

    core/      pure Kotlin/JVM engine. No Android types. All accounting lives here.
    app/       Android shell: SMS in, encrypted Room storage, Compose UI. Depends on :core.
    fixtures/  sample/ (synthetic, committed)   real/ (your SMS export, git-ignored)
    tools/     extract_fixtures.py (SMS export -> fixtures), gen_synthetic_fixtures.py
    docs/      BUILD_AND_TEST.md, UNVERIFIED.md

## Architecture

    SMS_RECEIVED broadcast ─┐
                            ├─> LedgerRepository (one mutex, background thread)
    Inbox backfill ─────────┘            │
                                         v
                              core: LedgerService.ingest
                 allowlist check -> OTP drop -> issuer templates -> draft
                 -> hash dedupe -> ledger row | statement proposal | review item
                                         │
                         LedgerStore port (RoomLedgerStore, SQLCipher)
                                         │
                 core: Ledger.balance / Reconciler / Dashboard.summarise
                                         v
                              Compose screens (thin view models)

`:core`
- `Model`, `Ledger`, `Reconciler`, `Matching`: money in paise, billed/unbilled maths,
  statement reconciliation, pending matching.
- `LedgerService`: the only code that changes the ledger. Ingest, dedupe, settle, refunds and
  reversals, stale authorisations, statement proposals, adjustments, review-queue actions.
- `Dashboard`: per-card status (NO_ANCHOR / RECONCILED / VARIANCE / STALE) and totals.
- `parsers/`: one file per issuer with a list of regex templates, shared shapes, ignore rules,
  amount/date/last4 helpers.
- `Backup`: JSON snapshot encrypted with AES-256-GCM, key from PBKDF2-HMAC-SHA256.
- `Store`: storage port plus an in-memory implementation used by tests.

`:app`
- `data/`: Room entities and DAO, SQLCipher database, Keystore-wrapped passphrase, adapter.
- `sms/`: broadcast receiver, multipart assembler, two-pass inbox backfill.
- `ui/`: lock gate, dashboard, card detail, statement entry/confirm, review queue, settings.

## Build order status

| Step | State |
|---|---|
| 1. `core` engine and tests | Written. Compiled and all tests run with the Kotlin 2.0.21 compiler bundled in Gradle (not through Gradle itself). |
| 2. Issuer parsers and fixture harness | Written and tested **against synthetic messages only**. No real fixtures yet. |
| 3. Encrypted backup/restore | Written, tested (round trip, wrong password, tampering). |
| 4. App Gradle setup, manifest, Room | Written. **Never compiled.** Dependency versions never resolved. |
| 5. SMS receiver and inbox backfill | Written. **Never compiled or run.** |
| 6. Compose UI | Written. **Never compiled or run.** |
| 7. Docs | This file, `docs/BUILD_AND_TEST.md`, `docs/UNVERIFIED.md`. |

Next for the owner: build on a machine with network access (`docs/BUILD_AND_TEST.md`), export real
SMS, and correct the templates against them (`fixtures/README.md`).

## Rules enforced

1. **No network.** The manifest asks for READ_SMS, RECEIVE_SMS and USE_BIOMETRIC only. The Gradle
   task `verify<Variant>NoNetworkPermissions` fails the build if the merged manifest of any variant
   contains INTERNET or another network permission. `ManifestPermissionsTest` checks the source
   manifest, and `SourcePolicyTest` fails on any network, analytics or logging API in the sources.
2. **Nothing leaves the phone.** `allowBackup=false`, extraction rules exclude every domain,
   `FLAG_SECURE` on the window, no logging calls anywhere. The database is SQLCipher-encrypted
   with a random passphrase wrapped by an Android Keystore key.
3. **Money is integer paise.** No floating point in the engine, storage or ingestion code
   (test-enforced). The JSON reader rejects fractional numbers.
4. **Precision over recall.** A message must match a template completely and every captured
   field must validate; anything else is `Unparsed` and goes to the review queue. Two templates
   disagreeing, a declined/failed wording, or an impossible date also mean `Unparsed`.
5. **Reconciliation never changes the ledger.** It reports a variance. Corrections are explicit
   ADJUSTMENT rows the user adds and can undo. Statement notices from SMS are proposals until
   confirmed.
6. **Templates are unverified.** Each one carries `// UNVERIFIED TEMPLATE: written without real fixtures`.
7. **Sender allowlist is user data.** Stored in the database, edited in Settings, per issuer.
   Defaults are editable starting points; Scapia and Kiwi start empty. Messages from other
   senders are not read further and not stored. OTP messages are dropped and never stored.

## How the numbers are defined

- Billed = latest confirmed statement total due, minus payments after the statement date,
  plus negative adjustments.
- Unbilled = purchases and fees after the statement date, minus refunds, plus positive adjustments.
- Pending authorisations are shown separately and are not in the headline.
- A card without a confirmed statement shows only the net of the messages seen, flagged NO_ANCHOR.

One design choice to know about: the default templates treat a "spent" alert as a posted
charge, not as a pending authorisation, because Indian issuers normally send one alert per
purchase and no separate settlement message. The pending/settle path exists in the engine and
can be switched on per template (`authorisation = true`).

## Run tests

    ./gradlew :core:test
    ./gradlew :app:testDebugUnitTest
    python3 -I tools/test_extract_fixtures.py

See `docs/BUILD_AND_TEST.md` for the full build, signing and first-run steps.
