package tracker.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BackupTest {

    private fun populated(): Fixture {
        val f = Fixture()
        f.service.setAllowlist("SCAPIA", emptyList())
        f.service.putSetting("backfill_months", "12")
        f.anchor(10_000)
        f.sms("SPEND|1234|500|Café \"Quote\" \\ Shop|REF001", 5)
        f.sms("AUTH|1234|70|Cafe", 6)
        f.sms("PAY|1234|1000", 7)
        f.sms("REVERSAL|1234|500|Café \"Quote\" \\ Shop|REF001", 8)
        f.sms("line one\nline two ₹1,250.00 \t tab", 9)
        f.sms("STMT|1234|9000|450|2026-12-05", 16)
        f.sms("STMT|1234|9100", 16, 13)
        f.service.addAdjustment("hdfc", LocalDate.of(2026, 10, 15), Paise(-12_345), "goodwill credit")
        f.sms("AUTH|1234|200|Swiggy", 20, 9)
        f.sms("AUTH|1234|200|Swiggy", 21, 9)
        f.sms("SPEND|1234|200|Swiggy", 22)
        return f
    }

    private val password get() = "correct horse battery".toCharArray()

    @Test fun `json round trip keeps every field`() {
        val snapshot = populated().service.snapshot()
        assertTrue(snapshot.txns.isNotEmpty() && snapshot.reviewItems.any { it.proposed != null } && snapshot.proposals.size == 2)
        assertEquals(snapshot, SnapshotCodec.fromJson(SnapshotCodec.toJson(snapshot)))
        val row = snapshot.txns.first()
        assertEquals(row, SnapshotCodec.txnFromJson(SnapshotCodec.txnToJson(row)))
    }

    @Test fun `encrypted round trip restores an identical ledger`() {
        val source = populated()
        val snapshot = source.service.snapshot()
        val file = BackupCodec.encrypt(snapshot, password)
        assertEquals(snapshot, BackupCodec.decrypt(file, password))

        val target = Fixture()
        target.sms("SPEND|1234|999|Old", 3)
        target.service.restore(BackupCodec.decrypt(file, password))
        assertEquals(snapshot, target.service.snapshot())
        val today = LocalDate.of(2026, 11, 23)
        assertEquals(source.service.dashboard(today), target.service.dashboard(today))
        // Dedupe state travels with the backup: re-importing the same SMS after restore is a no-op.
        assertEquals(IngestOutcome.Duplicate, target.sms("PAY|1234|1000", 7))
    }

    @Test fun `ciphertext does not contain plaintext and differs every time`() {
        val snapshot = populated().service.snapshot()
        val a = BackupCodec.encrypt(snapshot, password)
        val b = BackupCodec.encrypt(snapshot, password)
        assertFalse(a.contentEquals(b))
        val text = String(a, Charsets.ISO_8859_1)
        assertFalse(text.contains("Swiggy"))
        assertFalse(text.contains("cardId"))
        assertEquals("CTBK", text.substring(0, 4))
    }

    @Test fun `wrong password is rejected`() {
        val file = BackupCodec.encrypt(populated().service.snapshot(), password)
        val e = assertFailsWith<BackupException> { BackupCodec.decrypt(file, "correct horse batterx".toCharArray()) }
        assertTrue(e.message!!.contains("wrong password"))
    }

    @Test fun `any flipped bit is detected, header included`() {
        val file = BackupCodec.encrypt(LedgerSnapshot(settings = mapOf("k" to "v")), password)
        // salt (9), IV (25), first ciphertext byte (37), last tag byte
        for (index in listOf(9, 25, 37, file.size - 1)) {
            val tampered = file.copyOf()
            tampered[index] = (tampered[index].toInt() xor 0x01).toByte()
            assertFailsWith<BackupException>("byte $index") { BackupCodec.decrypt(tampered, password) }
        }
        assertFailsWith<BackupException> { BackupCodec.decrypt(file.copyOf(file.size - 1), password) }
        assertFailsWith<BackupException> { BackupCodec.decrypt(file + byteArrayOf(0), password) }
    }

    @Test fun `bad magic, version, iteration count and short files are rejected`() {
        val file = BackupCodec.encrypt(LedgerSnapshot(), password)
        assertFailsWith<BackupException> { BackupCodec.decrypt(ByteArray(10), password) }
        assertFailsWith<BackupException> { BackupCodec.decrypt(file.copyOf().also { it[0] = 'X'.code.toByte() }, password) }
        assertFailsWith<BackupException> { BackupCodec.decrypt(file.copyOf().also { it[4] = 2 }, password) }
        // iterations lowered in the header to 1: refused before any key derivation
        assertFailsWith<BackupException> {
            BackupCodec.decrypt(file.copyOf().also { it[5] = 0; it[6] = 0; it[7] = 0; it[8] = 1 }, password)
        }
        assertFailsWith<BackupException> { BackupCodec.encrypt(LedgerSnapshot(), CharArray(0)) }
        assertFailsWith<BackupException> { BackupCodec.encrypt(LedgerSnapshot(), password, iterations = 1000) }
    }

    @Test fun `header declares at least 310000 iterations and fresh salt and iv`() {
        val a = BackupCodec.encrypt(LedgerSnapshot(), password)
        val b = BackupCodec.encrypt(LedgerSnapshot(), password)
        val iterations = java.nio.ByteBuffer.wrap(a, 5, 4).getInt()
        assertTrue(iterations >= 310_000)
        assertEquals(1, a[4].toInt())
        assertNotEquals(a.copyOfRange(9, 25).toList(), b.copyOfRange(9, 25).toList())
        assertNotEquals(a.copyOfRange(25, 37).toList(), b.copyOfRange(25, 37).toList())
    }

    @Test fun `json reader refuses fractions and garbage`() {
        assertFailsWith<JsonException> { Json.parse("{\"a\": 1.5}") }
        assertFailsWith<JsonException> { Json.parse("{\"a\": 1e3}") }
        assertFailsWith<JsonException> { Json.parse("{\"a\": 1} x") }
        assertFailsWith<JsonException> { Json.parse("[1,") }
        assertEquals(mapOf("a" to listOf(1L, -2L, null, true, "x\ny₹")), Json.parse(" {\"a\" : [1, -2, null, true, \"x\\ny\\u20b9\"]} "))
        assertFailsWith<BackupException> { SnapshotCodec.fromJson("{\"format\": 99}") }
        assertFailsWith<BackupException> { SnapshotCodec.fromJson("not json") }
    }
}
