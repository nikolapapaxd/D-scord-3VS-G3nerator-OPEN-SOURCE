package com.discord.evs.generator.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY createdAt DESC")
    fun getAllLive(): LiveData<List<Account>>

    @Query("SELECT * FROM accounts ORDER BY createdAt DESC")
    suspend fun getAll(): List<Account>

    @Query("SELECT * FROM accounts WHERE status = :status ORDER BY createdAt DESC")
    fun getByStatusLive(status: String): LiveData<List<Account>>

    @Query("SELECT * FROM accounts WHERE status = :status ORDER BY createdAt DESC")
    suspend fun getByStatus(status: String): List<Account>

    @Query("SELECT * FROM accounts WHERE email LIKE '%' || :q || '%' OR token LIKE '%' || :q || '%' OR notes LIKE '%' || :q || '%' ORDER BY createdAt DESC")
    suspend fun search(q: String): List<Account>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Account?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(account: Account): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(accounts: List<Account>)

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("DELETE FROM accounts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("DELETE FROM accounts WHERE status = :status")
    suspend fun deleteByStatus(status: String)

    @Query("UPDATE accounts SET status = :status, checkedAt = :ts WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, ts: Long)

    @Query("UPDATE accounts SET status = :status, verified = :verified, emailVerified = :emailVerified, emailStatus = :emailStatus, phoneStatus = :phoneStatus, score = :score, scoreReason = :scoreReason, checkedAt = :ts, timeline = :timeline WHERE id = :id")
    suspend fun updateCheckResult(
        id: Long,
        status: String,
        verified: Boolean,
        emailVerified: Boolean,
        emailStatus: String,
        phoneStatus: String,
        score: Int,
        scoreReason: String,
        timeline: String,
        ts: Long
    )

    @Query("UPDATE accounts SET emailVerified = :v, emailStatus = :st, timeline = :timeline WHERE id = :id")
    suspend fun updateEmailOutcome(id: Long, v: Boolean, st: String, timeline: String)

    @Query("UPDATE accounts SET phone = :phone, phoneStatus = :st, timeline = :timeline WHERE id = :id")
    suspend fun updatePhoneOutcome(id: Long, phone: String, st: String, timeline: String)

    @Query("UPDATE accounts SET notes = :notes WHERE id = :id")
    suspend fun updateNotes(id: Long, notes: String)

    @Query("UPDATE accounts SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: Long, tags: String)

    @Query("UPDATE accounts SET timeline = :timeline WHERE id = :id")
    suspend fun updateTimeline(id: Long, timeline: String)

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE status = 'VALID'")
    suspend fun countValid(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE status = 'LOCKED'")
    suspend fun countLocked(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE status = 'INVALID'")
    suspend fun countInvalid(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE verified = 1")
    suspend fun countVerified(): Int

    @Query("SELECT * FROM accounts WHERE status = 'VALID' ORDER BY createdAt DESC")
    suspend fun getValid(): List<Account>

    @Query("SELECT * FROM accounts ORDER BY createdAt DESC LIMIT :n")
    suspend fun getRecent(n: Int): List<Account>
}
