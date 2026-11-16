package tracker.app

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hard rules 1 and 2, checked against the source manifest without an emulator.
 * (The Gradle task verify<Variant>NoNetworkPermissions checks the MERGED manifest, which also
 * covers permissions contributed by libraries.)
 */
class ManifestPermissionsTest {
    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun moduleFile(relative: String): File {
        val candidates = listOf(File(relative), File("app", relative))
        return candidates.firstOrNull { it.exists() } ?: error("not found: $relative (working dir " + File(".").absolutePath + ")")
    }

    private fun manifest(): Element {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(moduleFile("src/main/AndroidManifest.xml")).documentElement
    }

    private fun permissions(root: Element): Set<String> {
        val out = LinkedHashSet<String>()
        for (tag in listOf("uses-permission", "uses-permission-sdk-23")) {
            val nodes = root.getElementsByTagName(tag)
            for (i in 0 until nodes.length) out.add((nodes.item(i) as Element).getAttributeNS(androidNs, "name"))
        }
        return out
    }

    @Test fun `only sms and biometric permissions are requested and never INTERNET`() {
        val declared = permissions(manifest())
        assertFalse(declared.contains("android.permission.INTERNET"), "INTERNET must never be requested")
        assertEquals(
            setOf(
                "android.permission.READ_SMS",
                "android.permission.RECEIVE_SMS",
                "android.permission.USE_BIOMETRIC",
            ),
            declared,
        )
    }

    @Test fun `backup is disabled and extraction rules are set`() {
        val root = manifest()
        val app = root.getElementsByTagName("application").item(0) as Element
        assertEquals("false", app.getAttributeNS(androidNs, "allowBackup"))
        assertEquals("@xml/data_extraction_rules", app.getAttributeNS(androidNs, "dataExtractionRules"))
        assertEquals("@xml/full_backup_content", app.getAttributeNS(androidNs, "fullBackupContent"))

        val rules = moduleFile("src/main/res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val block = rules.substringAfter("<$section>").substringBefore("</$section>")
            for (domain in listOf("root", "file", "database", "sharedpref", "external")) {
                assertTrue(block.contains("<exclude domain=\"$domain\" path=\".\""), "$section must exclude $domain")
            }
            assertFalse(block.contains("<include"), "$section must not include anything")
        }
    }

    @Test fun `the sms receiver only accepts broadcasts from the system`() {
        val receivers = manifest().getElementsByTagName("receiver")
        assertEquals(1, receivers.length)
        val receiver = receivers.item(0) as Element
        assertEquals("android.permission.BROADCAST_SMS", receiver.getAttributeNS(androidNs, "permission"))
    }
}
