# What has not been verified

This is the honest list. The code was written in a sandbox with no access to Maven Central,
Google's Maven repository or the Gradle plugin portal, no Android SDK, no device, and no real
bank SMS.

## 1. Verified, and how

| What | How it was checked |
|---|---|
| `:core` main and test sources compile | Compiled with the Kotlin **2.0.21** compiler that ships inside the Gradle distribution, called directly (not through Gradle). The project pins Kotlin 2.2.0, which was not available. |
| `:core` tests pass (ledger, reconciler, matcher, engine, parsers, fixture harness, backup) | Run with a small reflective runner and a hand-written stand-in for `kotlin.test`. Not run under JUnit. |
| `ManifestPermissionsTest`, `SourcePolicyTest`, `FormatTest` in `:app` | Same local compiler and runner (they need only the JDK and `:core`). |
| `tools/test_extract_fixtures.py` | `python3 -I tools/test_extract_fixtures.py` |
| XML resources are well-formed | Parsed with Python's XML parser. Not processed by aapt. |

## 2. Never compiled, never run

- **`./gradlew` anything.** No Gradle build of this project has ever run. The wrapper files were
  generated offline in an empty directory and copied in.
- **Dependency versions** in `gradle/libs.versions.toml` (AGP 8.11.1, Kotlin 2.2.0,
  KSP 2.2.0-2.0.2, Compose BOM 2025.06.00, Room 2.7.2, biometric 1.1.0, sqlcipher-android 4.9.0,
  lifecycle 2.9.1, navigation 2.9.0, activity-compose 1.10.1, core-ktx 1.16.0, coroutines 1.10.2).
  Chosen from memory as a mutually compatible mid-2025 set. None was resolved. They are not
  the newest available in late 2026; that was deliberate, to stay with versions known to exist.
- **Every file under `app/src/main`.** It was type-checked only against hand-written stubs of the
  Android, AndroidX, Compose, Room and coroutines APIs. That catches Kotlin mistakes (syntax,
  imports, nullability, wrong argument names as the author remembered them) but proves nothing
  about the real signatures.
- **`MappersTest`, `SmsAssemblerTest`, `LedgerRepositoryTest`**: type-checked against the stubs, never run.
- **Room annotation processing (KSP)**: the DAO queries and entities have never been through Room's compiler.
- **The app on a phone**: nothing has run on any device or emulator. No instrumented tests exist.
- **The Gradle manifest check** (`verify<Variant>NoNetworkPermissions`): written against the AGP
  variant API from memory, never executed.

## 3. Every parser template is unverified

**No real bank SMS was available.** Each template was written from general knowledge of how
Indian card alerts are commonly worded and is marked in the source with
`// UNVERIFIED TEMPLATE: written without real fixtures`. The tests prove only that each template
matches the synthetic sentence it was written for.

Shared shapes (`core/.../parsers/Shapes.kt`), instantiated once per issuer as
`<issuer>.<name>` for HDFC, ICICI, Scapia, Kiwi, SBI and Axis:

| Template | Meaning |
|---|---|
| `spend.amt_spent_at` | "Rs.X spent on <card> at MERCHANT on DATE" |
| `spend.spent_amt_at` | "Spent Rs.X On <card> At MERCHANT On DATE" |
| `spend.date_then_merchant` | "INR X spent using <card> on DATE on MERCHANT" |
| `spend.thank_you` | "Thank you for using your <card> for INR X at MERCHANT" |
| `spend.txn_of` | "Transaction of Rs.X made on your <card> at MERCHANT" |
| `spend.debited` | "Rs.X debited from your <card> (via UPI) to MERCHANT" |
| `payment.received` | "Payment of Rs.X received towards your <card>" |
| `payment.we_received` | "We have received payment of Rs.X towards your <card>" |
| `refund.from_merchant` | "Refund of Rs.X from MERCHANT has been credited to your <card>" |
| `refund.plain` | "Refund of Rs.X has been credited to your <card>" |
| `reversal.txn_reversed` | "Your transaction of Rs.X at MERCHANT on <card> has been reversed" |
| `reversal.credited` | "Transaction reversal of Rs.X has been credited to your <card>" |
| `fee.charged` | "<Late payment fee / finance charges / interest / GST / annual fee ...> of Rs.X charged to your <card>" |
| `emi.debited` | "EMI of Rs.X has been debited to your <card>" |
| `statement.for_card` | "Statement for your <card> is generated. Total Amt Due ..., Min Amt Due ..., due date ..." |
| `statement.card_first` | "<card> Statement: Total Amount Due ...; Minimum Amount Due ...; Payment due by ..." |

Axis only (`Axis.kt`): `axis.spend.block_amount_first`, `axis.spend.block_card_first`
(multi-line "Spent / Card no. XX1234 / INR 499 / date time / MERCHANT / Avl Lmt" layouts).

That is 16 shared shapes x 6 issuers + 2 = **98 templates, all unverified.**

Also unverified, for the same reason:

- **The card phrase per issuer** (`CARD_PHRASE` in each issuer file), i.e. how each bank names the
  card in text. The Scapia and Kiwi phrases in particular include guessed partner-bank names.
- **Default sender-ID substrings** `HDFCBK`, `ICICIB`, `SBICRD`, `AXISBK`. Editable defaults, not
  facts. Scapia and Kiwi have none.
- **Ignore rules** (`IgnoreRules` in `Templates.kt`): what counts as an OTP, a promo, a reminder or
  a bank-account alert. A wrong rule here drops a message silently, so review these first against
  real samples. In particular: any unmatched message that merely mentions "OTP" is dropped rather
  than queued, to avoid storing a one-time code.
- **"Spent" alerts are treated as posted charges**, not pending authorisations (see README). If
  an issuer does send separate settlement messages, flag its spend template `authorisation = true`.
- **EMI conversion notices** have no template on purpose and go to the review queue.
- **Regex engine differences.** Templates were exercised on the JVM. Android uses ICU's engine.
  One known difference (a character class beginning with `:`) was removed and is guarded by a
  test; others may exist.

## 4. Behaviour that rests on assumptions about Android

- **Live and inbox timestamps match.** Dedupe hashes sender + timestamp + text. The receiver uses
  `SmsMessage.getTimestampMillis()`; the backfill uses the inbox `date_sent` column (falling
  back to `date`). These are expected to be equal for the same message. If they are not, a
  message seen live and then again in a backfill is not counted twice automatically: it is
  queued as "Possible duplicate" when the two timestamps are within 3 hours, and **counted twice
  if they differ by more**. Check this first on the phone (QA checklist, Ingestion).
- **Multipart messages** are joined in arrival order and assumed to equal the inbox text.
- **SMS permission for a sideloaded app** needs "Allow restricted settings". The steps in the
  docs and in the in-app help card describe recent Pixel builds from memory and were not checked
  on Android 16.
- **16 KB page size**: whether `sqlcipher-android` 4.9.0's native library loads on the Pixel 10
  was not checked. If the app shows "The database library could not be loaded", bump the version.
- **Keystore key is not bound to user authentication**, on purpose: the receiver must open the
  database while the phone is locked. The biometric gate protects the UI only.
- **Not direct-boot aware.** SMS arriving after a reboot and before the first unlock are picked
  up by the next backfill, not live.
- **Edge-to-edge** on Android 15+ is left to Material 3's `Scaffold`; status-bar icon contrast
  and insets were not looked at.
- **Android's "auto-revoke"** can still remove the SMS permission if the user does not turn off
  "Pause app activity if unused".

## 5. Known limits of the design

- One card per issuer; the six card ids are fixed.
- A card whose last-4 digits are not set is ignored entirely (its messages are not stored).
- Messages from an allowlisted sender about *other* cards or products of that bank that are not
  recognised as account alerts or promos will land in the review queue and must be dismissed.
- The statement date of an SMS statement notice is assumed from the card's statement day; the
  user must check it when confirming.
- A card with no confirmed statement shows only the net of the messages seen (NO_ANCHOR).
- Interest, fees and anything else the bank does not send an SMS for appear only as statement
  variance, to be resolved by an explicit adjustment.
- The schema is version 1 with no migrations yet and no destructive fallback.

## 6. Places most likely to need a fix at first compile

1. `gradle/libs.versions.toml`: a version that does not exist or a KSP/Kotlin mismatch.
2. `app/build.gradle.kts`: the `androidComponents.onVariants` manifest check and the signing block.
3. `app/src/main/kotlin/tracker/app/ui/*.kt`: Compose Material 3 parameter names and experimental
   opt-ins (`TopAppBar`, `Card(onClick)`, `FilterChip`, `OutlinedTextField`, `LinearProgressIndicator`).
4. `data/LedgerDatabase.kt`, `data/RoomLedgerStore.kt`, `data/LedgerDao.kt`: SQLCipher's
   `SupportOpenHelperFactory`, `runInTransaction`, and Room/KSP processing of the DAO.
5. `MainActivity.kt` and `ui/AppNav.kt`: `BiometricPrompt` with `FragmentActivity`,
   `ViewModelProvider`, `viewModelFactory { initializer { } }`, navigation-compose `composable`.
