package uk.co.logicscience.aegis.vpn

import android.content.Context
import android.util.Log
import uk.co.logicscience.aegis.BuildConfig
import uk.co.logicscience.aegis.config.Config
import uk.co.logicscience.aegis.data.AppDatabase
import kotlinx.coroutines.runBlocking
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Outcome of handling one DNS packet.
 *
 * Returned rather than stored on the resolver so that concurrent workers
 * do not race on shared mutable state.
 */
class DnsResult(val responsePacket: ByteArray?, val blocked: Boolean)

/** Cache key: a query only ever matches a cached entry of the same name and type. */
private data class CacheKey(val name: String, val qtype: Int)

/**
 * A cached outcome for one (name, qtype).
 *
 * [response] holds the raw upstream DNS message (header + question + answers)
 * for a forwarded lookup, or is null for a cached block decision. It is never
 * mutated after construction — callers copy it before patching in a fresh
 * transaction ID — so one instance can be handed to any number of readers
 * without locking.
 */
private class CacheEntry(
    val response: ByteArray?,
    val blocked: Boolean,
    val expiresAtNanos: Long
) {
    fun isExpired(): Boolean = System.nanoTime() >= expiresAtNanos
}

/**
 * Handles raw IPv4/UDP packets from the TUN device.
 * Parses the DNS query inside, decides block/forward, and builds
 * a complete IPv4/UDP response packet to write back to the TUN.
 *
 * Thread safety: handlePacket is called concurrently from a worker pool.
 * It holds no mutable per-query state; the blocklist sets are immutable
 * after assignment and marked @Volatile for safe publication. The response
 * cache is shared mutable state, so every access goes through [cache],
 * synchronized on itself — required because LinkedHashMap in access-order
 * mode reorders its internal list on `get`, which is not thread-safe even
 * for reads.
 */
class DnsResolver(context: Context) {
    private val db = AppDatabase.getDatabase(context)

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    @Volatile
    private var whitelistedDomains: Set<String> = emptySet()

    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(
        Config.DNS_CACHE_SIZE.coerceAtLeast(1), 0.75f, /* accessOrder = */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>): Boolean =
            size > Config.DNS_CACHE_SIZE
    }

    init {
        reloadBlocklists()
    }

    /**
     * Loads blocklists into memory. Blocking, so call from a background
     * thread only, and never from the packet path.
     */
    fun reloadBlocklists() {
        runBlocking {
            blockedDomains = db.blockedDomainDao().getEnabledBlockedDomains().toSet()
            whitelistedDomains = db.whitelistDao().getWhitelistedDomains().toSet()
        }
        // Block/whitelist status may have changed, so stale cache entries
        // could now give the wrong answer until this reload takes effect.
        synchronized(cache) { cache.clear() }
        log("blocklists loaded: ${blockedDomains.size} blocked, ${whitelistedDomains.size} whitelisted")
    }

    /**
     * @param packet full IPv4 packet from TUN (must be a private copy, not a reused buffer)
     * @param length valid bytes in packet
     * @param ihl    IPv4 header length in bytes
     */
    fun handlePacket(packet: ByteArray, length: Int, ihl: Int): DnsResult {
        return try {
            val dnsStart = ihl + 8
            val dnsLength = length - dnsStart
            if (dnsLength <= 12) return DnsResult(null, false)

            val dnsQuery = ByteArray(dnsLength)
            System.arraycopy(packet, dnsStart, dnsQuery, 0, dnsLength)

            val domainName = DnsPacket.parseQueryName(dnsQuery)
            val cacheKey = CacheKey(domainName.lowercase(), DnsPacket.parseQueryType(dnsQuery))

            val cached = cacheGet(cacheKey)
            if (cached != null) {
                val dnsResponse = if (cached.blocked) {
                    log("BLOCK (cached) $domainName")
                    createNxDomain(dnsQuery)
                } else {
                    log("CACHE HIT $domainName")
                    withTransactionId(cached.response!!, dnsQuery)
                }
                return DnsResult(DnsPacket.buildResponsePacket(packet, ihl, dnsResponse), cached.blocked)
            }

            var wasBlocked = false
            val dnsResponse: ByteArray = when {
                isWhitelisted(domainName) -> {
                    log("ALLOW (whitelist) $domainName")
                    val resp = forwardQuery(dnsQuery) ?: return DnsResult(null, false)
                    cacheForwardedResponse(cacheKey, resp)
                    resp
                }
                isBlocked(domainName) -> {
                    log("BLOCK $domainName")
                    wasBlocked = true
                    cachePut(cacheKey, CacheEntry(null, true, negativeTtlDeadline()))
                    createNxDomain(dnsQuery)
                }
                else -> {
                    log("FORWARD $domainName")
                    val resp = forwardQuery(dnsQuery) ?: return DnsResult(null, false)
                    cacheForwardedResponse(cacheKey, resp)
                    resp
                }
            }

            DnsResult(DnsPacket.buildResponsePacket(packet, ihl, dnsResponse), wasBlocked)
        } catch (e: Exception) {
            DnsResult(null, false)
        }
    }

    private fun cacheGet(key: CacheKey): CacheEntry? {
        synchronized(cache) {
            val entry = cache[key] ?: return null
            if (entry.isExpired()) {
                cache.remove(key)
                return null
            }
            return entry
        }
    }

    private fun cachePut(key: CacheKey, entry: CacheEntry) {
        synchronized(cache) { cache[key] = entry }
    }

    /**
     * Caches a forwarded response for the minimum TTL among its answer
     * records, capped at Config.DNS_CACHE_TTL. Responses with no answers
     * (NXDOMAIN, SERVFAIL, etc.) are not cached — those are transient
     * upstream outcomes, not something we want to pin in place.
     */
    private fun cacheForwardedResponse(key: CacheKey, response: ByteArray) {
        val ttlSeconds = DnsPacket.minAnswerTtl(response) ?: return
        if (ttlSeconds <= 0) return
        val cappedSeconds = minOf(ttlSeconds, Config.DNS_CACHE_TTL.toLong())
        cachePut(key, CacheEntry(response, false, System.nanoTime() + cappedSeconds * 1_000_000_000L))
    }

    private fun negativeTtlDeadline(): Long =
        System.nanoTime() + Config.DNS_CACHE_TTL * 1_000_000_000L

    /**
     * A cached response carries the transaction ID of whichever query first
     * populated the cache entry. The client that issued the *current* query
     * expects its own ID back, so return a copy with the ID patched in —
     * never mutate the cached array, which other threads may be reading
     * concurrently.
     */
    private fun withTransactionId(cachedResponse: ByteArray, currentQuery: ByteArray): ByteArray {
        val out = cachedResponse.copyOf()
        if (out.size >= 2 && currentQuery.size >= 2) {
            out[0] = currentQuery[0]
            out[1] = currentQuery[1]
        }
        return out
    }

    /**
     * Debug logging. Compiled out of release builds by BuildConfig.DEBUG,
     * and suppressed even in debug unless Config.LOG_DNS_QUERIES is on.
     *
     * This logs every domain the device resolves, so it must never be
     * enabled in a shipped build.
     */
    private fun log(message: String) {
        if (BuildConfig.DEBUG && Config.LOG_DNS_QUERIES) {
            Log.d("Aegis", message)
        }
    }

    private fun isWhitelisted(domain: String): Boolean =
        matchesSuffix(domain, whitelistedDomains)

    private fun isBlocked(domain: String): Boolean =
        matchesSuffix(domain, blockedDomains)

    /** Exact match, or any parent domain present in the set. */
    private fun matchesSuffix(domain: String, set: Set<String>): Boolean {
        if (set.isEmpty()) return false
        val d = domain.lowercase()
        if (set.contains(d)) return true
        val parts = d.split(".")
        for (i in 1 until parts.size) {
            if (set.contains(parts.subList(i, parts.size).joinToString("."))) return true
        }
        return false
    }

    private fun forwardQuery(dnsQuery: ByteArray): ByteArray? {
        return try {
            DatagramSocket().use { socket ->
                socket.soTimeout = Config.DNS_TIMEOUT_MS
                val addr = InetAddress.getByName(Config.PRIMARY_DNS)
                socket.send(DatagramPacket(dnsQuery, dnsQuery.size, addr, 53))
                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                socket.receive(resp)
                buf.copyOf(resp.length)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Flip the query into an NXDOMAIN response. */
    private fun createNxDomain(dnsQuery: ByteArray): ByteArray {
        val response = dnsQuery.copyOf()
        // QR=1 (response), RA=1, RCODE=3 (NXDOMAIN)
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = ((response[3].toInt() and 0x70) or 0x83).toByte()
        // Zero answer/authority/additional counts
        response[6] = 0; response[7] = 0
        response[8] = 0; response[9] = 0
        response[10] = 0; response[11] = 0
        return response
    }

}

/**
 * Stateless IPv4/UDP/DNS byte-level parsing and construction. Pure functions
 * of their inputs only, so they need no Context or resolver instance —
 * kept separate from DnsResolver for that reason, and to make them directly
 * unit-testable.
 */
object DnsPacket {
    /**
     * Build a full IPv4/UDP packet: swap src/dst addresses and ports
     * from the original query packet, attach the DNS response payload,
     * and recompute the IP checksum.
     */
    fun buildResponsePacket(query: ByteArray, ihl: Int, dnsPayload: ByteArray): ByteArray {
        val udpLength = 8 + dnsPayload.size
        val totalLength = ihl + udpLength
        val out = ByteArray(totalLength)

        // --- IPv4 header: copy then swap ---
        System.arraycopy(query, 0, out, 0, ihl)
        out[2] = ((totalLength shr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        for (i in 0..3) {
            out[12 + i] = query[16 + i]
            out[16 + i] = query[12 + i]
        }
        out[8] = 64 // TTL
        out[10] = 0; out[11] = 0
        val ipCsum = checksum(out, 0, ihl)
        out[10] = ((ipCsum shr 8) and 0xFF).toByte()
        out[11] = (ipCsum and 0xFF).toByte()

        // --- UDP header: swap ports ---
        out[ihl] = query[ihl + 2]
        out[ihl + 1] = query[ihl + 3]
        out[ihl + 2] = query[ihl]
        out[ihl + 3] = query[ihl + 1]
        out[ihl + 4] = ((udpLength shr 8) and 0xFF).toByte()
        out[ihl + 5] = (udpLength and 0xFF).toByte()
        out[ihl + 6] = 0; out[ihl + 7] = 0 // UDP checksum optional for IPv4

        System.arraycopy(dnsPayload, 0, out, ihl + 8, dnsPayload.size)
        return out
    }

    private fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (length % 2 != 0) {
            sum += ((data[offset + length - 1].toInt() and 0xFF) shl 8).toLong()
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return sum.inv().toInt() and 0xFFFF
    }

    /** Extract the query name from a raw DNS message. */
    fun parseQueryName(dns: ByteArray): String {
        val labels = mutableListOf<String>()
        var pos = 12 // skip DNS header
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) break
            if ((len and 0xC0) == 0xC0) break // compression pointer, stop
            pos++
            if (pos + len > dns.size) break
            labels.add(String(dns, pos, len))
            pos += len
        }
        return labels.joinToString(".")
    }

    /** Extract the QTYPE of the (first) question, or -1 if the message is too short to hold one. */
    fun parseQueryType(dns: ByteArray): Int {
        val afterName = skipName(dns, 12)
        if (afterName + 2 > dns.size) return -1
        return ((dns[afterName].toInt() and 0xFF) shl 8) or (dns[afterName + 1].toInt() and 0xFF)
    }

    /**
     * Minimum TTL, in seconds, across all answer records in a raw upstream
     * DNS response — the standard bound for how long the response as a
     * whole may be cached. Returns null when the message has no answers
     * (NXDOMAIN, SERVFAIL, a truncated read, etc.), signaling "don't cache".
     */
    fun minAnswerTtl(dns: ByteArray): Long? {
        if (dns.size < 12) return null
        val qdcount = ((dns[4].toInt() and 0xFF) shl 8) or (dns[5].toInt() and 0xFF)
        val ancount = ((dns[6].toInt() and 0xFF) shl 8) or (dns[7].toInt() and 0xFF)
        if (ancount <= 0) return null

        var pos = 12
        repeat(qdcount) {
            pos = skipName(dns, pos) + 4 // QTYPE + QCLASS
        }

        var minTtl: Long? = null
        repeat(ancount) {
            if (pos >= dns.size) return minTtl
            pos = skipName(dns, pos)
            if (pos + 10 > dns.size) return minTtl // TYPE+CLASS+TTL+RDLENGTH
            val ttl = ((dns[pos + 4].toLong() and 0xFF) shl 24) or
                    ((dns[pos + 5].toLong() and 0xFF) shl 16) or
                    ((dns[pos + 6].toLong() and 0xFF) shl 8) or
                    (dns[pos + 7].toLong() and 0xFF)
            val rdlength = ((dns[pos + 8].toInt() and 0xFF) shl 8) or (dns[pos + 9].toInt() and 0xFF)
            minTtl = minTtl?.let { minOf(it, ttl) } ?: ttl
            pos += 10 + rdlength
        }
        return minTtl
    }

    /** Advances past one name field (label sequence and/or compression pointer), wherever it occurs in the message. */
    private fun skipName(dns: ByteArray, start: Int): Int {
        var pos = start
        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> return pos + 2
                else -> pos += 1 + len
            }
        }
        return pos
    }
}
