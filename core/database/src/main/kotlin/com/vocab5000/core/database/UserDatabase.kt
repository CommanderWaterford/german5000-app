package com.vocab5000.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Writable learner database. Schemas are exported to `core/database/schemas`; every
 * version change needs an explicit, tested [Migration]. Destructive fallback is never used.
 */
@Database(
    entities = [
        UserEntryEntity::class,
        CardStateEntity::class,
        ReviewEventEntity::class,
        SessionEntity::class,
        SessionItemEntity::class,
        PracticeResultEntity::class,
        AssessmentEntity::class,
        EntitlementCacheEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun userEntryDao(): UserEntryDao
    abstract fun cardDao(): CardDao
    abstract fun reviewEventDao(): ReviewEventDao
    abstract fun sessionDao(): SessionDao
    abstract fun practiceDao(): PracticeDao
    abstract fun assessmentDao(): AssessmentDao
    abstract fun entitlementDao(): EntitlementDao

    companion object {
        const val NAME = "german5000-user.db"

        /** Add migrations here in order; tests in MigrationTest cover each step. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun create(context: Context, name: String = NAME): UserDatabase =
            Room.databaseBuilder(context.applicationContext, UserDatabase::class.java, name)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
