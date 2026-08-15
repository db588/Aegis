package com.example.aegis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update
import androidx.room.Delete
import kotlinx.coroutines.flow.Flow

@Dao
interface BlocklistDao {
    @Query("SELECT * FROM blocklists ORDER BY name ASC")
    fun getAllBlocklists(): Flow<List<Blocklist>>

    @Query("SELECT * FROM blocklists WHERE isEnabled = 1")
    suspend fun getEnabledBlocklists(): List<Blocklist>

    @Query("SELECT * FROM blocklists WHERE id = :id")
    suspend fun getBlocklist(id: Long): Blocklist?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(blocklist: Blocklist): Long

    @Update
    suspend fun update(blocklist: Blocklist)

    @Delete
    suspend fun delete(blocklist: Blocklist)
}

@Dao
interface BlockedDomainDao {
    @Query("SELECT domain FROM blocked_domains WHERE blocklistId IN (SELECT id FROM blocklists WHERE isEnabled = 1)")
    suspend fun getEnabledBlockedDomains(): List<String>

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE blocklistId = :blocklistId")
    suspend fun getCountForBlocklist(blocklistId: Long): Int

    @Query("DELETE FROM blocked_domains WHERE blocklistId = :blocklistId")
    suspend fun deleteForBlocklist(blocklistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(domains: List<BlockedDomain>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(domain: BlockedDomain)
}

@Dao
interface WhitelistDao {
    @Query("SELECT domain FROM whitelist")
    suspend fun getWhitelistedDomains(): Set<String>

    @Query("SELECT * FROM whitelist ORDER BY addedAt DESC")
    fun getAllWhitelisted(): Flow<List<WhitelistedDomain>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(domain: WhitelistedDomain)

    @Delete
    suspend fun delete(domain: WhitelistedDomain)

    @Query("DELETE FROM whitelist WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)
}

@Database(
    entities = [Blocklist::class, BlockedDomain::class, WhitelistedDomain::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun blocklistDao(): BlocklistDao
    abstract fun blockedDomainDao(): BlockedDomainDao
    abstract fun whitelistDao(): WhitelistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "aegis_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
