package com.example.aegis.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class BlocklistManager(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val cache = ConcurrentHashMap<Long, Set<String>>()

    /**
     * Parse blocklist content in Pi-hole format:
     * - Comments: # or !
     * - Addresses: 0.0.0.0 domain.com or 127.0.0.1 domain.com
     * - Hosts format: 0.0.0.0 domain.com
     * - Domain only: domain.com
     */
    suspend fun parseBlocklistContent(content: String): Set<String> {
        return withContext(Dispatchers.Default) {
            content.lines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .mapNotNull { line ->
                    when {
                        line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1") -> {
                            line.split(Regex("\\s+")).getOrNull(1)?.lowercase()
                        }
                        line.contains(" ") -> {
                            line.split(Regex("\\s+")).getOrNull(1)?.lowercase()
                        }
                        else -> line.lowercase()
                    }
                }
                .filter { it != null && isValidDomain(it) }
                .map { it!! }
                .toSet()
        }
    }

    /**
     * Import blocklist from raw text content (used by the file picker)
     */
    suspend fun importBlocklistFromContent(
        name: String,
        description: String,
        content: String
    ): Long {
        val domains = parseBlocklistContent(content)

        val blocklist = Blocklist(
            name = name,
            description = description,
            isCustom = true,
            domainCount = domains.size
        )

        val id = db.blocklistDao().insert(blocklist)
        db.blockedDomainDao().insertAll(domains.map { BlockedDomain(it, id) })
        cache[id] = domains
        return id
    }

    /**
     * Import blocklist from file
     */
    suspend fun importBlocklistFromFile(
        name: String,
        description: String,
        file: File
    ): Long {
        val content = file.readText()
        val domains = parseBlocklistContent(content)
        
        val blocklist = Blocklist(
            name = name,
            description = description,
            isCustom = true,
            domainCount = domains.size
        )
        
        val id = db.blocklistDao().insert(blocklist)
        
        val domainEntities = domains.map { domain ->
            BlockedDomain(domain, id)
        }
        
        db.blockedDomainDao().insertAll(domainEntities)
        cache[id] = domains
        
        return id
    }

    /**
     * Create a custom blocklist (e.g., social media domains)
     */
    suspend fun createCustomBlocklist(
        name: String,
        description: String,
        domains: List<String>
    ): Long {
        val normalizedDomains = domains.map { it.lowercase() }.toSet()
        
        val blocklist = Blocklist(
            name = name,
            description = description,
            isCustom = true,
            domainCount = normalizedDomains.size
        )
        
        val id = db.blocklistDao().insert(blocklist)
        
        val domainEntities = normalizedDomains.map { domain ->
            BlockedDomain(domain, id)
        }
        
        db.blockedDomainDao().insertAll(domainEntities)
        cache[id] = normalizedDomains
        
        return id
    }

    /**
     * Download and import blocklist from URL
     */
    suspend fun importBlocklistFromUrl(
        name: String,
        description: String,
        url: String
    ): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val content = URL(url).readText(Charsets.UTF_8)
                val domains = parseBlocklistContent(content)
                
                val blocklist = Blocklist(
                    name = name,
                    description = description,
                    url = url,
                    isCustom = false,
                    domainCount = domains.size
                )
                
                val id = db.blocklistDao().insert(blocklist)
                
                val domainEntities = domains.map { domain ->
                    BlockedDomain(domain, id)
                }
                
                db.blockedDomainDao().insertAll(domainEntities)
                cache[id] = domains
                id
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    suspend fun addDomainsToBlocklist(blocklistId: Long, domains: List<String>) {
        val normalizedDomains = domains.map { it.lowercase() }
        val entities = normalizedDomains.map { domain ->
            BlockedDomain(domain, blocklistId)
        }
        db.blockedDomainDao().insertAll(entities)
        cache.remove(blocklistId) // Invalidate cache
    }

    suspend fun updateBlocklistStatus(blocklistId: Long, enabled: Boolean) {
        val blocklist = db.blocklistDao().getBlocklist(blocklistId)
        if (blocklist != null) {
            db.blocklistDao().update(blocklist.copy(isEnabled = enabled))
            cache.remove(blocklistId)
        }
    }

    suspend fun deleteBlocklist(blocklistId: Long) {
        val blocklist = db.blocklistDao().getBlocklist(blocklistId)
        if (blocklist != null) {
            db.blockedDomainDao().deleteForBlocklist(blocklistId)
            db.blocklistDao().delete(blocklist)
            cache.remove(blocklistId)
        }
    }

    suspend fun addToWhitelist(domain: String) {
        db.whitelistDao().insert(WhitelistedDomain(domain.lowercase()))
    }

    suspend fun removeFromWhitelist(domain: String) {
        db.whitelistDao().deleteByDomain(domain.lowercase())
    }

    private fun isValidDomain(domain: String): Boolean {
        if (domain.isEmpty() || domain.length > 253) return false
        if (domain.startsWith("-") || domain.endsWith("-")) return false
        
        return domain.matches(Regex("^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$"))
    }
}
