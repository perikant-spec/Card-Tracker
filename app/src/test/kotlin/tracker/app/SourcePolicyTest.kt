package tracker.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Cheap guards for the hard rules, run as plain unit tests:
 *  - no logging API anywhere (SMS text, amounts and card digits can then never be logged);
 *  - no network API anywhere;
 *  - no floating point in the engine or in storage/ingestion code (money is integer paise).
 * Failure messages list file names and rule names only.
 */
class SourcePolicyTest {

    private fun root(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("repository root not found")
    }

    private fun sources(relative: String): List<File> =
        File(root(), relative).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun violations(files: List<File>, rules: Map<String, Regex>): List<String> {
        val out = ArrayList<String>()
        for (file in files) {
            val text = file.readText()
            for ((name, regex) in rules) if (regex.containsMatchIn(text)) out.add(file.name + ": " + name)
        }
        return out
    }

    private val production = listOf("core/src/main", "app/src/main")

    @Test fun `no logging api is used in production code`() {
        val rules = mapOf(
            "android.util.Log" to Regex("android\\.util\\.Log"),
            "Log call" to Regex("\\bLog\\.(?:v|d|i|w|e|wtf)\\("),
            "Timber" to Regex("\\bTimber\\b"),
            "println" to Regex("\\bprintln\\("),
            "printStackTrace" to Regex("printStackTrace\\("),
            "java.util.logging" to Regex("java\\.util\\.logging"),
        )
        val found = production.flatMap { violations(sources(it), rules) }
        assertTrue(found.isEmpty(), found.toString())
    }

    @Test fun `no network api is used in production code`() {
        val rules = mapOf(
            "java.net" to Regex("\\bjava\\.net\\."),
            "javax.net" to Regex("\\bjavax\\.net\\."),
            "HttpURLConnection" to Regex("HttpURLConnection"),
            "okhttp" to Regex("okhttp", RegexOption.IGNORE_CASE),
            "retrofit" to Regex("retrofit", RegexOption.IGNORE_CASE),
            "ktor" to Regex("\\bio\\.ktor\\b"),
            "WebView" to Regex("\\bWebView\\b"),
            "Socket" to Regex("\\b(?:Server)?Socket\\("),
            "firebase" to Regex("firebase", RegexOption.IGNORE_CASE),
            "crashlytics" to Regex("crashlytics", RegexOption.IGNORE_CASE),
        )
        val found = production.flatMap { violations(sources(it), rules) }
        assertTrue(found.isEmpty(), found.toString())
    }

    @Test fun `no floating point in the engine, storage or ingestion code`() {
        val rules = mapOf(
            "Float/Double type" to Regex("\\b(?:Float|Double)\\b"),
            "toFloat/toDouble" to Regex("\\bto(?:Float|Double)(?:OrNull)?\\("),
            "BigDecimal" to Regex("\\bBigDecimal\\b"),
        )
        val files = sources("core/src/main") +
            sources("app/src/main/kotlin/tracker/app/data") +
            sources("app/src/main/kotlin/tracker/app/sms")
        // Comments may say "never Float/Double"; only code is checked.
        val found = ArrayList<String>()
        for (file in files) {
            val code = file.readLines().filter { line ->
                val t = line.trim()
                !(t.startsWith("//") || t.startsWith("*") || t.startsWith("/*"))
            }.joinToString("\n")
            for ((name, regex) in rules) if (regex.containsMatchIn(code)) found.add(file.name + ": " + name)
        }
        assertTrue(found.isEmpty(), found.toString())
    }

    @Test fun `build files declare no network or analytics dependency`() {
        val text = File(root(), "gradle/libs.versions.toml").readText() + File(root(), "app/build.gradle.kts").readText()
        for (banned in listOf("okhttp", "retrofit", "ktor", "firebase", "crashlytics", "sentry", "play-services", "volley", "timber")) {
            assertTrue(!text.contains(banned, ignoreCase = true), "banned dependency keyword: $banned")
        }
    }
}
