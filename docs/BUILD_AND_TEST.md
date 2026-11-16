# Build, test, install

Nothing below has been executed by the author. The authoring sandbox had no access to Maven
Central, Google's Maven repository or the Gradle plugin portal. Expect to fix things at the
first build; `docs/UNVERIFIED.md` lists the likeliest places.

## 1. Tools on the build laptop

1. **JDK 17 or newer.** Android Studio's bundled JDK (JBR 21) is fine. Check with `java -version`.
2. **Android Studio** (current stable). In SDK Manager install *Android SDK Platform 36* and the
   latest *Android SDK Build-Tools* and *Platform-Tools*.
3. Tell Gradle where the SDK is, either by opening the project once in Android Studio (it writes
   `local.properties`) or by hand:

       echo "sdk.dir=/path/to/Android/Sdk" > local.properties

   `local.properties` is git-ignored. Do not commit it.
4. **Gradle wrapper.** `gradlew`, `gradlew.bat` and `gradle/wrapper/` are committed and pinned to
   Gradle 8.14.3, so no system Gradle is needed. If you ever need to regenerate them:

       gradle wrapper --gradle-version 8.14.3

## 2. Build and test, in this order

    ./gradlew :core:test                 # pure-JVM engine, parsers, backup
    ./gradlew :app:testDebugUnitTest     # mappers, manifest and source policy checks, UI mapping
    ./gradlew :app:assembleDebug         # first full Android compile
    ./gradlew :app:assembleRelease       # after the keystore step below

The no-network check runs automatically before `assemble*`, `install*` and the unit tests. To run
it alone:

    ./gradlew :app:verifyDebugNoNetworkPermissions :app:verifyReleaseNoNetworkPermissions

After the first successful build, commit the generated `app/schemas/tracker.app.data.LedgerDatabase/1.json`
so future database migrations can be checked against it.

### If a dependency version does not resolve

Versions are pinned in `gradle/libs.versions.toml` and were chosen from memory. If Gradle reports
"Could not find ...", open that file and move the named library to the nearest version listed on
its release page. Keep these pairs consistent:

- `ksp` must start with the exact `kotlin` version (for Kotlin 2.2.0: `2.2.0-2.0.x`).
- AGP 8.11.x needs Gradle 8.13 or newer and supports `compileSdk 36`.
- The Compose compiler plugin (`kotlin-compose`) always uses the Kotlin version.
- Room 2.7.x is required for KSP2.

## 3. Release signing

Create a keystore once and keep it somewhere safe outside the repository. If you lose it, a new
build cannot be installed over the old one without uninstalling (which deletes the ledger; export
a backup first).

    keytool -genkeypair -v -keystore ~/keys/card-tracker.jks -alias card-tracker \
            -keyalg RSA -keysize 4096 -validity 10000

Create `keystore.properties` in the repository root (git-ignored, never commit it):

    storeFile=/home/you/keys/card-tracker.jks
    storePassword=...
    keyAlias=card-tracker
    keyPassword=...

Then:

    ./gradlew :app:assembleRelease
    # -> app/build/outputs/apk/release/app-release.apk

Without `keystore.properties` the release build is unsigned (`app-release-unsigned.apk`) and will
not install. For a first look use the debug build instead.

## 4. Install on the Pixel

Enable Developer options and USB debugging on the phone, connect it, then:

    adb devices
    adb install -r app/build/outputs/apk/release/app-release.apk

(or `./gradlew :app:installDebug` for the debug build).

## 5. First run on the Pixel 10

1. Open the app and unlock with fingerprint or PIN. A screen lock must be set on the phone.
2. **Settings > Request permission.** Allow SMS.
   - If the SMS toggle is greyed out or Android says the setting is restricted (normal for an
     app installed from a file): *Open app info* > three-dot menu at the top right >
     **Allow restricted settings** > confirm with your screen lock > go back and grant SMS.
3. **App info > App battery usage > Unrestricted.**
4. **App info > turn off "Pause app activity if unused"** (also labelled "Manage app if unused").
   Otherwise Android withdraws the SMS permission after a few months of not opening the app.
5. **Settings > Cards.** For each card enter the last four digits exactly as the bank's messages
   show them, the statement day and the due day. A card with no digits is not tracked.
6. **Settings > Sender allowlist.** Check the defaults. For Scapia and Kiwi (empty by default)
   find the sender IDs first:

       python3 tools/extract_fixtures.py list-senders sms-export.xml

   and type them in. Only messages from listed senders are ever read.
7. **Settings > Inbox backfill.** Leave 12 months, tap *Run backfill*. Watch the counters.
8. **Review queue.** Expect it to be busy at first: the templates are unverified, so many real
   messages will not match. Classify, link or dismiss. Then improve the templates (section 7).
9. **Enter statements.** For each card: Settings > *Opening statement (anchor)* or the card's
   *Enter a statement*. Use the figures from the bank's statement. The first statement per card is
   the anchor; from the second one on the screen shows the variance against the ledger.
10. **Reconcile.** For a non-zero variance choose *Find missing* (look in the review queue) or
    *Add adjustment*, which writes an explicit row you can undo. Repeat every statement.
11. **Settings > Encrypted backup > Export** once things look right. Keep the password.

## 6. Manual QA checklist

Lock and privacy
- [ ] Cold start shows the lock screen; cancelling the prompt shows no data.
- [ ] Switching away and back locks again. Rotating the phone does not.
- [ ] Screenshots are blocked and the recent-apps preview is blank.
- [ ] `adb shell dumpsys package tracker.app | grep permission` lists no INTERNET.
- [ ] `adb logcat` while a bank SMS arrives shows no message text, amount or card digits from this app.
- [ ] `adb backup` / device transfer does not carry the app's data.

Ingestion
- [ ] A live SMS from an allowlisted sender appears on the dashboard without opening the app first.
- [ ] After that, running the backfill reports it under "Already known" and the total is unchanged
      (this checks that the live path and the inbox path hash a message identically).
- [ ] Running the backfill twice in a row changes nothing the second time.
- [ ] An OTP message from the bank creates nothing, not even a review item.
- [ ] A message from a sender not on the allowlist creates nothing.
- [ ] A bank-account debit alert plus the card's "payment received" alert reduce billed once.
- [ ] Removing a sender from the allowlist stops its messages being processed.

Ledger
- [ ] Two same-amount purchases at one merchant on one day both count, or one is queued as a
      possible duplicate; neither is silently merged.
- [ ] A reversal cancels its purchase; a later "refund credited" notice for the same purchase is
      queued, not credited a second time.
- [ ] A statement SMS shows up as a notice on the card and changes no figure until confirmed.
- [ ] Confirming a statement with a variance, adding the adjustment and undoing it returns the
      status badge to Variance.
- [ ] Deleting a statement returns the card to its previous state.

Backup
- [ ] Export, uninstall, reinstall, restore: dashboard is identical.
- [ ] Restore with a wrong password fails with a clear message and leaves the ledger untouched.

After a reboot
- [ ] A bank SMS received before the first unlock/open is picked up live, or at the latest by
      the next backfill.

## 7. Making the parsers real

The templates are guesses. To replace them with tested ones:

1. Export SMS with *SMS Backup & Restore* (SyncTech) to XML and copy the file to the laptop.
2. `python3 tools/extract_fixtures.py list-senders export.xml`
3. `python3 tools/extract_fixtures.py extract export.xml --senders 'HDFCBK|ICICIB|...' --out fixtures/real`
4. Follow `fixtures/README.md` to turn redacted lines into `fixtures/sample/*.jsonl` cases.
5. `./gradlew :core:test` and edit `core/src/main/kotlin/tracker/core/parsers/<Issuer>.kt` until
   the cases pass. Remove a template's UNVERIFIED comment only when a real sample covers it.

Never commit anything from `fixtures/real/`.
