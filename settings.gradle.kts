rootProject.name = "card-tracker"

// Pure-Kotlin engine first (parser, ledger, reconciliation). The Android app
// module is added later and depends on :core.
include(":core")
