package com.budgetingtool.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts WHERE deleted = 0 ORDER BY name")
    fun observeActive(): Flow<List<Account>>

    @Query("SELECT * FROM accounts WHERE deleted = 0")
    suspend fun active(): List<Account>

    @Query("SELECT * FROM accounts WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<Account>

    @Query("SELECT * FROM accounts WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Account>

    @Upsert
    suspend fun upsert(items: List<Account>)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE deleted = 0 ORDER BY kind, name")
    fun observeActive(): Flow<List<Category>>

    @Query("SELECT * FROM categories WHERE deleted = 0")
    suspend fun active(): List<Category>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT * FROM categories WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<Category>

    @Query("SELECT * FROM categories WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Category>

    @Upsert
    suspend fun upsert(items: List<Category>)
}

@Dao
interface BudgetDao {
    @Query("SELECT * FROM budgets WHERE deleted = 0")
    fun observeActive(): Flow<List<Budget>>

    @Query("SELECT * FROM budgets WHERE deleted = 0")
    suspend fun active(): List<Budget>

    @Query("SELECT * FROM budgets WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<Budget>

    @Query("SELECT * FROM budgets WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Budget>

    @Upsert
    suspend fun upsert(items: List<Budget>)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules WHERE deleted = 0 ORDER BY pattern")
    fun observeActive(): Flow<List<CategoryRule>>

    @Query("SELECT * FROM rules WHERE deleted = 0")
    suspend fun active(): List<CategoryRule>

    @Query("SELECT * FROM rules WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<CategoryRule>

    @Query("SELECT * FROM rules WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<CategoryRule>

    @Upsert
    suspend fun upsert(items: List<CategoryRule>)
}

@Dao
interface TxnDao {
    @Query("SELECT * FROM transactions WHERE deleted = 0 ORDER BY date DESC, updatedAt DESC")
    fun observeActive(): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE deleted = 0")
    suspend fun active(): List<Txn>

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND date BETWEEN :startDay AND :endDay")
    suspend fun between(startDay: Long, endDay: Long): List<Txn>

    /** Includes deleted rows so a transaction the user deleted is not re-imported. */
    @Query("SELECT importKey FROM transactions WHERE importKey IS NOT NULL AND importKey IN (:keys)")
    suspend fun existingImportKeys(keys: List<String>): List<String>

    @Query("SELECT COUNT(*) FROM transactions WHERE deleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM transactions WHERE deleted = 0 AND source = 'MANUAL' AND pending = 1")
    suspend fun pendingManual(): List<Txn>

    @Query("SELECT * FROM transactions WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<Txn>

    @Query("SELECT * FROM transactions WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Txn>

    @Upsert
    suspend fun upsert(items: List<Txn>)
}

@Dao
interface StatementDao {
    @Query("SELECT * FROM statements WHERE deleted = 0 ORDER BY periodEnd DESC")
    fun observeActive(): Flow<List<Statement>>

    @Query("SELECT COUNT(*) FROM statements WHERE deleted = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM statements WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<Statement>

    @Query("SELECT * FROM statements WHERE updatedAt > :since")
    suspend fun changedSince(since: Long): List<Statement>

    @Upsert
    suspend fun upsert(items: List<Statement>)
}

@Database(
    entities = [Account::class, Category::class, Budget::class, Txn::class, CategoryRule::class, Statement::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetDao(): BudgetDao
    abstract fun ruleDao(): RuleDao
    abstract fun txnDao(): TxnDao
    abstract fun statementDao(): StatementDao

    companion object {
        /** 1.2.0: billing cycles. Adds each card's closing/due day and a table of statements. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `closingDay` INTEGER")
                db.execSQL("ALTER TABLE `accounts` ADD COLUMN `dueDay` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `statements` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, " +
                        "`periodStart` INTEGER NOT NULL, `periodEnd` INTEGER NOT NULL, `closingBalanceCents` INTEGER, " +
                        "`dueDate` INTEGER, `minPaymentCents` INTEGER, `updatedAt` INTEGER NOT NULL, " +
                        "`deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_statements_accountId` ON `statements` (`accountId`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "budget.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
