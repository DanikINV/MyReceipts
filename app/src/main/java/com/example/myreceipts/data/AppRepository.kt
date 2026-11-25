package com.example.myreceipts.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

data class AuthUser(
    val id: Long,
    val role: String,
    val fullName: String,
    val email: String
)

class AppRepository(context: Context) {
    private val database = AppDatabase(context)

    fun getUser(userId: Long): AuthUser? {
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT id, role, full_name, email FROM users WHERE id = ? AND is_active = 1",
                arrayOf(userId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                return AuthUser(
                    cursor.getLong(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3)
                )
            }
        }
    }

    fun getCompanyName(userId: Long): String? {
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT c.name FROM users u JOIN management_companies c ON c.id = u.company_id WHERE u.id = ?",
                arrayOf(userId.toString())
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }
    }

    fun getCompanyId(userId: Long): Long? {
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT company_id FROM users WHERE id = ? AND role = 'manager'",
                arrayOf(userId.toString())
            ).use { cursor ->
                return if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }
    }

    fun getManagers(): List<StaffManager> {
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT id, full_name, email, company_name, is_active FROM v_managers ORDER BY full_name",
                null
            ).use { cursor ->
                val managers = mutableListOf<StaffManager>()
                while (cursor.moveToNext()) {
                    managers += StaffManager(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getInt(4) == 1
                    )
                }
                return managers
            }
        }
    }

    fun getReceipts(companyId: Long?): List<StaffReceipt> {
        val filter = if (companyId == null) "" else " WHERE company_id = ?"
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT id, company_id, company_name, street_address, account_number, payer_name, " +
                    "period, due_date, total_kop, payment_status, is_manual " +
                    "FROM v_receipts_list$filter ORDER BY period DESC, due_date",
                if (companyId == null) null else arrayOf(companyId.toString())
            ).use { cursor ->
                val receipts = mutableListOf<StaffReceipt>()
                while (cursor.moveToNext()) {
                    receipts += StaffReceipt(
                        id = cursor.getLong(0),
                        companyId = cursor.getLong(1),
                        companyName = cursor.getString(2),
                        address = cursor.getString(3),
                        accountNumber = cursor.getString(4),
                        payerName = cursor.getString(5),
                        period = cursor.getString(6),
                        dueDate = cursor.getString(7),
                        totalKop = cursor.getLong(8),
                        status = cursor.getString(9),
                        isManual = cursor.getInt(10) == 1
                    )
                }
                return receipts
            }
        }
    }

    fun setReceiptStatus(actorId: Long, receiptId: Long, status: String, comment: String?) {
        require(status == "paid" || status == "unpaid") { "Недопустимый статус" }
        database.initialize().use { db ->
            db.beginTransaction()
            try {
                val context = ContentValues().apply {
                    put("key", "current_user_id")
                    put("value", actorId.toString())
                }
                db.insertWithOnConflict("app_context", null, context, SQLiteDatabase.CONFLICT_REPLACE)
                val values = ContentValues().apply {
                    put("payment_status", status)
                    put("status_source", "manual")
                    put("status_comment", comment)
                }
                val updated = db.update("receipts", values, "id = ?", arrayOf(receiptId.toString()))
                if (updated == 0) throw IllegalArgumentException("Квитанция не найдена")
                db.delete("app_context", "key = ?", arrayOf("current_user_id"))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun createManager(
        actorId: Long,
        fullName: String,
        email: String,
        phone: String?,
        companyName: String,
        password: String
    ) {
        require(fullName.isNotBlank()) { "Введите ФИО" }
        require(email.contains("@")) { "Введите корректную почту" }
        require(companyName.isNotBlank()) { "Введите управляющую компанию" }
        require(password.length >= 4) { "Пароль должен содержать минимум 4 символа" }

        database.initialize().use { db ->
            db.beginTransaction()
            try {
                db.rawQuery(
                    "SELECT id FROM users WHERE id = ? AND role = 'admin' AND is_active = 1",
                    arrayOf(actorId.toString())
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw IllegalArgumentException("Недостаточно прав")
                }
                val companyValues = ContentValues().apply { put("name", companyName.trim()) }
                db.insertWithOnConflict(
                    "management_companies",
                    null,
                    companyValues,
                    SQLiteDatabase.CONFLICT_IGNORE
                )
                val companyId = db.rawQuery(
                    "SELECT id FROM management_companies WHERE name = ? COLLATE NOCASE",
                    arrayOf(companyName.trim())
                ).use { cursor ->
                    if (!cursor.moveToFirst()) throw IllegalStateException("Не удалось создать компанию")
                    cursor.getLong(0)
                }
                val managerValues = ContentValues().apply {
                    put("role", "manager")
                    put("full_name", fullName.trim())
                    put("email", email.trim())
                    put("phone", phone?.trim()?.takeIf(String::isNotEmpty))
                    put("password_hash", "plain:$password")
                    put("must_change_password", 1)
                    put("company_id", companyId)
                }
                db.insertOrThrow("users", null, managerValues)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    fun authenticate(email: String, password: String): AuthUser? {
        if (password.isBlank()) return null

        val normalizedEmail = email.trim()
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT id, role, full_name, email, password_hash, is_active " +
                    "FROM users WHERE email = ? COLLATE NOCASE",
                arrayOf(normalizedEmail)
            ).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow("is_active")) != 1) {
                    return null
                }

                val passwordHash = cursor.getString(cursor.getColumnIndexOrThrow("password_hash"))
                val matchesDemoPassword = passwordHash.startsWith("hash_") && password == "1234"
                val matchesStoredPassword = passwordHash == "plain:$password"
                if (!matchesDemoPassword && !matchesStoredPassword) return null

                return AuthUser(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    role = cursor.getString(cursor.getColumnIndexOrThrow("role")),
                    fullName = cursor.getString(cursor.getColumnIndexOrThrow("full_name")),
                    email = cursor.getString(cursor.getColumnIndexOrThrow("email"))
                )
            }
        }
    }

    fun registerResident(fullName: String, email: String, password: String): AuthUser {
        require(fullName.isNotBlank()) { "Введите имя" }
        require(email.contains("@")) { "Введите корректный email" }
        require(password.length >= 4) { "Пароль должен содержать минимум 4 символа" }

        database.initialize().use { db ->
            db.beginTransaction()
            try {
                db.rawQuery(
                    "SELECT id FROM users WHERE email = ? COLLATE NOCASE",
                    arrayOf(email.trim())
                ).use { cursor ->
                    if (cursor.moveToFirst()) throw IllegalArgumentException("Пользователь уже существует")
                }

                val values = android.content.ContentValues().apply {
                    put("role", "resident")
                    put("full_name", fullName.trim())
                    put("email", email.trim())
                    put("phone", null as String?)
                    put("password_hash", "plain:$password")
                }
                val id = db.insertOrThrow("users", null, values)
                db.setTransactionSuccessful()
                return AuthUser(id, "resident", fullName.trim(), email.trim())
            } finally {
                db.endTransaction()
            }
        }
    }

    fun loadResidentSummary(userId: Long): ResidentSummary? {
        database.initialize().use { db ->
            db.rawQuery(
                "SELECT a.street_address, r.period, r.due_date, r.payment_status, " +
                    "COALESCE((SELECT SUM(amount_kop) FROM receipt_items WHERE receipt_id = r.id), 0) AS total_kop " +
                    "FROM addresses a " +
                    "LEFT JOIN receipts r ON r.address_id = a.id " +
                    "WHERE a.user_id = ? " +
                    "ORDER BY r.period DESC LIMIT 1",
                arrayOf(userId.toString())
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val address = cursor.getString(0)
                val period = cursor.getString(1)
                val dueDate = cursor.getString(2)
                val status = cursor.getString(3)
                val totalKop = cursor.getLong(4)
                return ResidentSummary(address, period, dueDate, status, totalKop)
            }
        }
    }
}

data class StaffManager(
    val id: Long,
    val fullName: String,
    val email: String,
    val companyName: String,
    val active: Boolean
)

data class StaffReceipt(
    val id: Long,
    val companyId: Long,
    val companyName: String,
    val address: String,
    val accountNumber: String,
    val payerName: String,
    val period: String,
    val dueDate: String,
    val totalKop: Long,
    val status: String,
    val isManual: Boolean
)

data class ResidentSummary(
    val address: String,
    val period: String?,
    val dueDate: String?,
    val status: String?,
    val totalKop: Long
)
