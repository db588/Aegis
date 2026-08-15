package com.example.aegis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blocklists")
data class Blocklist(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val description: String = "",
    val isEnabled: Boolean = true,
    val isCustom: Boolean = false,
    val url: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val domainCount: Int = 0
)

@Entity(tableName = "blocked_domains", primaryKeys = ["domain", "blocklistId"])
data class BlockedDomain(
    val domain: String,
    val blocklistId: Long,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "whitelist")
data class WhitelistedDomain(
    @PrimaryKey
    val domain: String,
    val addedAt: Long = System.currentTimeMillis()
)

data class BlocklistWithDomains(
    val blocklist: Blocklist,
    val domainCount: Int
)
