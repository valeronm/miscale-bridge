package com.miscalebridge.app.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WeighInEntity::class],
    version = 1,
    exportSchema = false,   // single-version schema for now; flip on when migrations land
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun weighInDao(): WeighInDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "miscale_bridge.db")
                .build()
    }
}
