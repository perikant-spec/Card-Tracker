pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "card-tracker"

// :core is the pure-Kotlin engine (parsers, ledger, reconciliation, backup). No Android types.
// :app is the Android shell (SMS in, Room storage, Compose UI) and depends on :core.
include(":core")
include(":app")
