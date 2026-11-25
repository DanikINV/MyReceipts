package com.example.myreceipts.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream

class AppDatabase(context: Context) {
    private val applicationContext = context.applicationContext

    fun initialize(): SQLiteDatabase {
        val databaseFile = File(
            applicationContext.getDatabasePath(DATABASE_NAME).parentFile,
            DATABASE_NAME
        )
        databaseFile.parentFile?.mkdirs()

        val needsSeedData = databaseFile.exists() && runCatching {
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE
            ).use { database ->
                database.execSQL("PRAGMA foreign_keys = ON")
                database.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'users'",
                    null
                ).use { tableCursor ->
                    tableCursor.moveToFirst()
                    if (tableCursor.getInt(0) == 0) return@use true
                }
                database.rawQuery("SELECT COUNT(*) FROM users", null).use { userCursor ->
                    userCursor.moveToFirst()
                    userCursor.getInt(0) == 0
                }
            }
        }.getOrDefault(true)

        if (!databaseFile.exists() || needsSeedData) {
            if (databaseFile.exists()) databaseFile.delete()
            applicationContext.assets.open(DATABASE_NAME).use { input ->
                FileOutputStream(databaseFile).use { output ->
                    input.copyTo(output)
                }
            }
        }

        val database = SQLiteDatabase.openDatabase(
            databaseFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE
        )
        database.execSQL("PRAGMA foreign_keys = ON")
        return database
    }

    companion object {
        const val DATABASE_NAME = "me_receipts.db"
    }
}
