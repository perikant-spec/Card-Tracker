package tracker.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CardEntity::class,
        TxnEntity::class,
        StatementEntity::class,
        ProposalEntity::class,
        ReviewEntity::class,
        AllowlistEntity::class,
        SettingEntity::class,
        ProcessedSmsEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun dao(): LedgerDao

    companion object {
        const val FILE_NAME = "ledger.db"

        /**
         * Add a Migration here whenever `version` above is raised. There is deliberately no
         * destructive fallback: a missing migration must fail loudly, not wipe the ledger.
         */
        val MIGRATIONS: Array<Migration> = arrayOf()

        /** Opens the SQLCipher-encrypted database. [passphrase] is copied; the caller may wipe its array. */
        fun open(context: Context, passphrase: ByteArray): LedgerDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(passphrase.copyOf())
            return Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, FILE_NAME)
                .openHelperFactory(factory)
                .addMigrations(*MIGRATIONS)
                .build()
        }
    }
}
