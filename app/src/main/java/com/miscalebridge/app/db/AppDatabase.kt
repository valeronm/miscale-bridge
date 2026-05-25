package com.miscalebridge.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WeighInEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun weighInDao(): WeighInDao

    companion object {
        /**
         * v1 → v2: source provenance for cross-app HC import.
         *   - `data_origin_package_name` — owning app's package; nullable so
         *     ALTER works without a default. Existing rows were all written
         *     by this app, so backfill them with our own id rather than
         *     leaving them ambiguous.
         *   - `data_origin_app_label` — human-readable app label snapshot,
         *     resolved via PackageManager at import time. Stays null for the
         *     backfilled rows; UI falls back to "this app" for our own rows.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE weigh_ins ADD COLUMN data_origin_package_name TEXT")
                db.execSQL("ALTER TABLE weigh_ins ADD COLUMN data_origin_app_label TEXT")
                db.execSQL(
                    "UPDATE weigh_ins SET data_origin_package_name = 'com.miscalebridge.app' " +
                        "WHERE data_origin_package_name IS NULL"
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "miscale_bridge.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
