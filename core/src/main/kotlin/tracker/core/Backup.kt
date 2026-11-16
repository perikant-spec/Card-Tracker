package tracker.core

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Snapshot <-> JSON. Dates are ISO strings, money is integer paise. */
object SnapshotCodec {
    const val FORMAT = 1

    // ---- writers

    private fun txnMap(t: Txn): Map<String, Any?> = linkedMapOf(
        "id" to t.id, "cardId" to t.cardId, "date" to t.date.toString(), "amount" to t.amount.value,
        "type" to t.type.name, "state" to t.state.name, "merchant" to t.merchant, "ref" to t.ref,
        "linkedTo" to t.linkedTo, "sourceHash" to t.sourceHash, "note" to t.note,
    )

    fun txnToJson(t: Txn): String = Json.write(txnMap(t))

    fun toJson(s: LedgerSnapshot): String = Json.write(
        linkedMapOf(
            "format" to FORMAT.toLong(),
            "cards" to s.cards.map {
                linkedMapOf("id" to it.id, "issuer" to it.issuer, "last4" to it.last4, "statementDay" to it.statementDay.toLong(), "dueDay" to it.dueDay.toLong())
            },
            "txns" to s.txns.map { txnMap(it) },
            "statements" to s.statements.map {
                linkedMapOf(
                    "cardId" to it.cardId, "statementDate" to it.statementDate.toString(), "totalDue" to it.totalDue.value,
                    "minDue" to it.minDue?.value, "dueDate" to it.dueDate?.toString(),
                )
            },
            "proposals" to s.proposals.map {
                linkedMapOf(
                    "id" to it.id, "cardId" to it.cardId, "statementDate" to it.statementDate.toString(),
                    "totalDue" to it.totalDue.value, "minDue" to it.minDue?.value, "dueDate" to it.dueDate?.toString(),
                    "sourceHash" to it.sourceHash, "createdAtMs" to it.createdAtMs,
                )
            },
            "reviewItems" to s.reviewItems.map {
                linkedMapOf(
                    "id" to it.id, "kind" to it.kind.name, "status" to it.status.name, "createdAtMs" to it.createdAtMs,
                    "sender" to it.sender, "body" to it.body, "receivedAtMs" to it.receivedAtMs, "smsHash" to it.smsHash,
                    "cardId" to it.cardId, "txnId" to it.txnId, "candidateIds" to it.candidateIds,
                    "proposed" to it.proposed?.let { p -> txnMap(p) }, "reason" to it.reason,
                )
            },
            "processed" to s.processed.map { linkedMapOf("hash" to it.hash, "bodyHash" to it.bodyHash, "receivedAtMs" to it.receivedAtMs) },
            "allowlist" to s.allowlist,
            "settings" to s.settings,
        ),
    )

    // ---- readers (strict: a wrong type is an error, never a default)

    private fun bad(what: String): Nothing = throw BackupException("backup content is malformed: $what")

    private fun obj(v: Any?, what: String): Map<*, *> = v as? Map<*, *> ?: bad(what)
    private fun list(v: Any?, what: String): List<*> = v as? List<*> ?: bad(what)
    private fun Map<*, *>.str(key: String): String = this[key] as? String ?: bad(key)
    private fun Map<*, *>.strOrNull(key: String): String? = this[key]?.let { it as? String ?: bad(key) }
    private fun Map<*, *>.long(key: String): Long = this[key] as? Long ?: bad(key)
    private fun Map<*, *>.longOrNull(key: String): Long? = this[key]?.let { it as? Long ?: bad(key) }
    private fun Map<*, *>.date(key: String): LocalDate = parseDate(str(key), key)
    private fun Map<*, *>.dateOrNull(key: String): LocalDate? = strOrNull(key)?.let { parseDate(it, key) }

    private fun parseDate(text: String, key: String): LocalDate = try {
        LocalDate.parse(text)
    } catch (e: RuntimeException) {
        bad(key)
    }

    private inline fun <reified E : Enum<E>> enumOf(name: String): E =
        enumValues<E>().firstOrNull { it.name == name } ?: bad("unknown value $name")

    private fun txnFrom(m: Map<*, *>): Txn = Txn(
        id = m.str("id"), cardId = m.str("cardId"), date = m.date("date"), amount = Paise(m.long("amount")),
        type = enumOf<TxnType>(m.str("type")), state = enumOf<TxnState>(m.str("state")),
        merchant = m.strOrNull("merchant"), ref = m.strOrNull("ref"), linkedTo = m.strOrNull("linkedTo"),
        sourceHash = m.strOrNull("sourceHash"), note = m.strOrNull("note"),
    )

    fun txnFromJson(text: String): Txn = try {
        txnFrom(obj(Json.parse(text), "txn"))
    } catch (e: JsonException) {
        throw BackupException("stored row is not valid JSON", e)
    }

    fun fromJson(text: String): LedgerSnapshot {
        val root = try {
            obj(Json.parse(text), "root")
        } catch (e: JsonException) {
            throw BackupException("backup content is not valid JSON", e)
        }
        if (root.long("format") != FORMAT.toLong()) throw BackupException("unsupported backup content format")
        return LedgerSnapshot(
            cards = list(root["cards"], "cards").map {
                val m = obj(it, "card")
                CardConfig(m.str("id"), m.str("issuer"), m.str("last4"), m.long("statementDay").toInt(), m.long("dueDay").toInt())
            },
            txns = list(root["txns"], "txns").map { txnFrom(obj(it, "txn")) },
            statements = list(root["statements"], "statements").map {
                val m = obj(it, "statement")
                Statement(m.str("cardId"), m.date("statementDate"), Paise(m.long("totalDue")), m.longOrNull("minDue")?.let { v -> Paise(v) }, m.dateOrNull("dueDate"))
            },
            proposals = list(root["proposals"], "proposals").map {
                val m = obj(it, "proposal")
                StatementProposal(
                    m.str("id"), m.str("cardId"), m.date("statementDate"), Paise(m.long("totalDue")),
                    m.longOrNull("minDue")?.let { v -> Paise(v) }, m.dateOrNull("dueDate"), m.strOrNull("sourceHash"), m.long("createdAtMs"),
                )
            },
            reviewItems = list(root["reviewItems"], "reviewItems").map {
                val m = obj(it, "reviewItem")
                ReviewItem(
                    id = m.str("id"), kind = enumOf<ReviewKind>(m.str("kind")), status = enumOf<ReviewStatus>(m.str("status")),
                    createdAtMs = m.long("createdAtMs"), sender = m.strOrNull("sender"), body = m.strOrNull("body"),
                    receivedAtMs = m.longOrNull("receivedAtMs"), smsHash = m.strOrNull("smsHash"), cardId = m.strOrNull("cardId"),
                    txnId = m.strOrNull("txnId"),
                    candidateIds = list(m["candidateIds"], "candidateIds").map { c -> c as? String ?: bad("candidateIds") },
                    proposed = m["proposed"]?.let { p -> txnFrom(obj(p, "proposed")) }, reason = m.strOrNull("reason"),
                )
            },
            processed = list(root["processed"], "processed").map {
                val m = obj(it, "processed")
                ProcessedSms(m.str("hash"), m.str("bodyHash"), m.long("receivedAtMs"))
            },
            allowlist = obj(root["allowlist"], "allowlist").entries.associate { (k, v) ->
                (k as? String ?: bad("allowlist")) to list(v, "allowlist").map { p -> p as? String ?: bad("allowlist") }
            },
            settings = obj(root["settings"], "settings").entries.associate { (k, v) ->
                (k as? String ?: bad("settings")) to (v as? String ?: bad("settings"))
            },
        )
    }
}

/**
 * Password-encrypted backup file.
 *
 * Layout (all big-endian):
 *   magic "CTBK" (4) | version (1) | PBKDF2 iterations (4) | salt (16) | IV (12) | AES-256-GCM ciphertext + 16-byte tag
 * The 37 header bytes are bound as GCM associated data, so any change to them is detected.
 * Key = PBKDF2WithHmacSHA256(password, salt, iterations) -> 256 bits.
 */
object BackupCodec {
    const val VERSION: Byte = 1
    const val DEFAULT_ITERATIONS = 310_000
    const val MIN_ITERATIONS = 310_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte())
    private const val HEADER_BYTES = 4 + 1 + 4 + SALT_BYTES + IV_BYTES

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(
        snapshot: LedgerSnapshot,
        password: CharArray,
        random: SecureRandom = SecureRandom(),
        iterations: Int = DEFAULT_ITERATIONS,
    ): ByteArray {
        if (password.isEmpty()) throw BackupException("password must not be empty")
        if (iterations < MIN_ITERATIONS) throw BackupException("iteration count too low")
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put(VERSION).putInt(iterations).put(salt).put(iv).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        val body = cipher.doFinal(SnapshotCodec.toJson(snapshot).toByteArray(Charsets.UTF_8))
        return header + body
    }

    fun decrypt(file: ByteArray, password: CharArray): LedgerSnapshot {
        if (file.size < HEADER_BYTES + TAG_BITS / 8) throw BackupException("not a card-tracker backup (too short)")
        val buf = ByteBuffer.wrap(file)
        val magic = ByteArray(4).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException("not a card-tracker backup")
        val version = buf.get()
        if (version != VERSION) throw BackupException("unsupported backup version $version")
        val iterations = buf.getInt()
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw BackupException("backup header is invalid")
        val salt = ByteArray(SALT_BYTES).also { buf.get(it) }
        val iv = ByteArray(IV_BYTES).also { buf.get(it) }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(file, 0, HEADER_BYTES)
            cipher.doFinal(file, HEADER_BYTES, file.size - HEADER_BYTES)
        } catch (e: GeneralSecurityException) {
            throw BackupException("wrong password, or the backup file is damaged", e)
        }
        return SnapshotCodec.fromJson(String(plain, Charsets.UTF_8))
    }
}
